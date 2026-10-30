# Banc d'essai du RAG T24 (sept modules : TT, AC, DE, EB, LD, MD, LC).
# Usage : .\run-rag-tests.ps1 -Label avant   (puis)   .\run-rag-tests.ps1 -Label apres
# Necessite : backend (8080), FAISS (8001), Ollama (11434) et Oracle demarres. Ecrit results-<Label>.json et report-<Label>.txt.
# Le script est volontairement en ASCII : les regex utilisent "." pour les caracteres accentues.
param([string]$Label = "run", [string]$Filter = "")

$root = $PSScriptRoot
$base = "http://localhost:8080/api"
$utf8 = [Text.Encoding]::UTF8

$login = Invoke-RestMethod -Uri "$base/auth/login" -Method POST -ContentType "application/json" -Body (@{username="admin";password="Admin@2024"} | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

$names = @{}
foreach ($m in @("TT","AC","DE","EB","LD","MD","LC")) {
    $set = New-Object 'System.Collections.Generic.HashSet[string]'
    foreach ($l in [IO.File]::ReadAllLines("$root\data\$m.names", $utf8)) { [void]$set.Add($l.Trim()) }
    # Identifiants cités dans le texte même de la documentation (ex. exemples : comptes CHARG.LIQ.ACCT) : documentés, donc pas inventés.
    if (Test-Path "$root\data\$m.doctokens") {
        foreach ($l in [IO.File]::ReadAllLines("$root\data\$m.doctokens", $utf8)) { [void]$set.Add($l.Trim()) }
    }
    $names[$m] = $set
}

function Ask($q, $mod, $cid) {
    $body = $utf8.GetBytes((@{question=$q; module=$mod; tableName=$null; conversationId=$cid} | ConvertTo-Json -Compress))
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest -Uri "$base/chat" -Method POST -ContentType "application/json; charset=utf-8" -Headers $headers -Body $body -TimeoutSec 400 -UseBasicParsing
        $obj = $utf8.GetString($r.RawContentStream.ToArray()) | ConvertFrom-Json
        $obj | Add-Member -NotePropertyName secs -NotePropertyValue ([int]$sw.Elapsed.TotalSeconds) -Force
        return $obj
    } catch { return $null }
}

function Sentences($text) {
    $out = @()
    foreach ($s in ($text -split '(?<=[.!?])\s+|\r?\n+')) {
        $n = ($s.ToLower() -replace '[^a-z0-9 ]', '' -replace '\s+', ' ').Trim()
        if ($n.Length -ge 25) { $out += $n }
    }
    return $out
}

function Check($t, $resp, $prev, $mod) {
    $f = @()
    $a = $resp.answer
    $words = ($a -split '\s+' | Where-Object { $_ }).Count
    if ($t.min -and $words -lt $t.min) { $f += "trop court ($words<$($t.min))" }
    if ($t.max -and $words -gt $t.max) { $f += "trop long ($words>$($t.max))" }
    if (([regex]::Matches($a, '(?i)\b(the|of|is|are|this|specifies|identifies|validation rules|must be)\b')).Count -ge 2) { $f += "anglais" }
    if ($a -match '^\S+ \([A-Z0-9.,_ ]+\) :') { $f += "dump brut" }
    if ($a -match '(?i)^\s*bonjour') { $f += "salutation" }
    if ($a -match '(?im)^\s*(question|utilisateur|user|vous)\s*:') { $f += "question inventee" }
    $qUpper = $t.q.ToUpper()
    foreach ($m in [regex]::Matches($a, '\b[A-Z][A-Z0-9]*(?:\.[A-Z0-9]+)+\b')) {
        $tok = $m.Value
        if ($qUpper.Contains($tok)) { continue }
        $known = $false
        foreach ($k in $names.Keys) { if ($names[$k].Contains($tok)) { $known = $true; break } }
        if (-not $known) { $f += "identifiant inconnu: $tok"; break }
    }
    foreach ($re in @($t.must))    { if ($re -and $a -notmatch $re) { $f += "manque /$re/" } }
    foreach ($re in @($t.mustNot)) { if ($re -and $a -match $re)    { $f += "interdit /$re/" } }
    if ($t.struct -and $a -notmatch '(?m)^(#{1,4} |\s*- |\s*\d+\. |\s*\* |\s*\|)') { $f += "non structure" }
    if ($t.tableau -and $a -notmatch '(?m)^\s*\|.+\|.+\|') { $f += "pas de tableau" }
    $tables = @($resp.sourceFields | ForEach-Object { $_.tableName } | Select-Object -Unique)
    if ($t.srcOnly) { foreach ($x in $tables) { if ($x -notmatch $t.srcOnly) { $f += "source hors sujet: $x" } } }
    if ($t.srcNone -and $tables.Count -gt 0) { $f += "source inattendue: $($tables -join ',')" }
    if ($t.srcAny -and $tables.Count -eq 0) { $f += "aucune source" }
    if ($t.noRepeat -and $prev.Count -gt 0) {
        $cur = Sentences $a
        if ($cur.Count -gt 0) {
            $seen = @{}; foreach ($p in $prev) { foreach ($s in (Sentences $p)) { $seen[$s] = $true } }
            $rep = @($cur | Where-Object { $seen.ContainsKey($_) }).Count
            $ratio = [math]::Round($rep / $cur.Count, 2)
            if ($ratio -gt 0.35) { $f += "repetition ($ratio)" }
        }
    }
    return $f
}

# ---- Jeu de tests -------------------------------------------------------------------------------------
$T = @()
function C($cat, $id, $mod, $turns) { $script:T += [pscustomobject]@{ cat=$cat; id=$id; mod=$mod; turns=$turns } }

# 1) Conversation demandee : LD (4 tours)
# NB: mustNot cible le CHAMP "EVENT" isolement mentionne hors sujet (bug rapporte : LD.SCHEDULE.EXCEPTION.EVENT
# cite arbitrairement sur une question generale) - jamais un vrai nom de TABLE qui contient "EVENT" en partie de
# son nom compose (ex. AC.EVENT, table reelle et legitimement pertinente) : (?<![.\w]) / (?![.\w]) exclut ce cas.
$EVENT_FIELD_ONLY = '(?<![.\w])EVENT(?![.\w])'
C "CONV" "CONV-LD" "LD" @(
  @{ q="Quels types d'informations peut-on retrouver dans la documentation du module LD ?"; min=110; mustNot=$EVENT_FIELD_ONLY; must='LD\.|LOANS|LOAN|DEPOSIT|pr.t|d.p.t'; struct=$true },
  @{ q="Peux-tu me donner un exemple concret ?"; min=35; mustNot=$EVENT_FIELD_ONLY; noRepeat=$true },
  @{ q="Explique davantage."; min=60; mustNot=$EVENT_FIELD_ONLY; noRepeat=$true },
  @{ q="Et quelles sont les regles de validation ?"; min=40; must='(?i)valid|format|caract|obligatoire|valeur'; mustNot=$EVENT_FIELD_ONLY; noRepeat=$true }
)

# 2) Questions generales sur un module (sans champ EVENT ni details arbitraires)
foreach ($m in @("TT","AC","DE","EB","MD","LC")) {
  C "GEN" "GEN-$m" $m @( @{ q="Que peut-on trouver dans la documentation du module $m ?"; min=100; mustNot=$EVENT_FIELD_ONLY; struct=$true } )
}

# 3) Champs
C "FIELD" "FIELD-AC" "AC" @( @{ q="explique le champ customer dans account"; min=60; must='CUSTOMER'; srcOnly='^ACCOUNT'; srcAny=$true } )
C "FIELD" "FIELD-LD" "LD" @( @{ q="a quoi sert interest.spread dans ld.loans.and.deposits ?"; min=60; must='INTEREST\.SPREAD'; srcOnly='^LD\.LOANS\.AND\.DEPOSITS$'; srcAny=$true } )
C "FIELD" "FIELD-LC" "LC" @( @{ q="explique instrument.type dans dr.discrepant.type"; min=50; must='INSTRUMENT\.TYPE'; srcOnly='^DR\.DISCREPANT\.TYPE'; srcAny=$true } )
C "FIELD" "FIELD-TT" "TT" @( @{ q="a quoi sert tt.hp.version dans tt.teller.hp.versions ?"; min=40; must='TT\.HP\.VERSION'; srcOnly='^TT\.TELLER\.HP\.VERSIONS$'; srcAny=$true } )
C "FIELD" "FIELD-EB" "EB" @( @{ q="a quoi sert tracer.date dans eb.free.message ?"; min=50; must='TRACER\.DATE'; srcOnly='^EB\.FREE\.MESSAGE$'; srcAny=$true } )
C "FIELD" "FIELD-DE" "DE" @( @{ q="explique le champ osn.no dans de.sent.swift"; min=35; must='OSN\.NO'; srcOnly='^DE\.SENT\.SWIFT$'; srcAny=$true } )
C "FIELD" "FIELD-MD" "MD" @( @{ q="a quoi sert md.deal dans md.beneficiary ?"; min=35; must='MD\.DEAL'; srcOnly='^MD\.BENEFICIARY$'; srcAny=$true } )

# 4) Tables (role + tableau des champs)
C "TABLE" "TABLE-AC" "AC" @( @{ q="explique la table account"; min=80; must='ACCOUNT'; srcAny=$true; tableau=$true } )
C "TABLE" "TABLE-LD" "LD" @( @{ q="explique la table ld.loans.and.deposits"; min=80; must='LD\.LOANS\.AND\.DEPOSITS'; srcAny=$true; tableau=$true } )
C "TABLE" "TABLE-LC" "LC" @( @{ q="explique la table dr.discrepant.type"; min=60; must='DR\.DISCREPANT\.TYPE'; srcAny=$true; tableau=$true } )
C "TABLE" "TABLE-TT" "TT" @( @{ q="explique la table tt.teller.hp.versions"; min=50; must='TT\.TELLER\.HP\.VERSIONS'; srcAny=$true; tableau=$true } )
C "TABLE" "TABLE-EB" "EB" @( @{ q="explique la table eb.free.message"; min=50; must='EB\.FREE\.MESSAGE'; srcAny=$true; tableau=$true } )
C "TABLE" "TABLE-DE" "DE" @( @{ q="explique la table de.sent.swift"; min=50; must='DE\.SENT\.SWIFT'; srcAny=$true; tableau=$true } )
C "TABLE" "TABLE-MD" "MD" @( @{ q="explique la table md.beneficiary"; min=50; must='MD\.BENEFICIARY'; srcAny=$true; tableau=$true } )

# 5) Controle de saisie (uniquement ce qui est documente)
C "VALID" "VALID-AC" "AC" @( @{ q="quelles sont les regles de validation du champ currency dans account ?"; min=40; must='(?i)SSS|3 caract|1 . 3|existant'; srcOnly='^ACCOUNT' } )
C "VALID" "VALID-LD" "LD" @( @{ q="quelles sont les regles de validation du champ interest.spread dans ld.loans.and.deposits ?"; min=40; must='(?i)100|9 caract|d.cimal|n.gatif|interdit'; srcOnly='^LD\.LOANS\.AND\.DEPOSITS$' } )
C "VALID" "VALID-LC" "LC" @( @{ q="quelles sont les valeurs autorisees du champ instrument.type dans dr.discrepant.type ?"; min=25; must='EXPORT|IMPORT'; srcOnly='^DR\.DISCREPANT\.TYPE' } )
C "VALID" "VALID-EB" "EB" @( @{ q="quelle est la longueur maximale du champ narrative dans eb.free.message ?"; min=15; must='50|35'; srcOnly='^EB\.FREE\.MESSAGE$' } )

# 6bis) Tables liees a un theme, avec role + champs principaux demandes (pas une enumeration brute)
C "TOPIC" "TOPIC-LD-CREDIT" "LD" @( @{ q="quelles sont les tables documentees qui contiennent des informations sur les contrats de credit ? Pour chacune, precise son role et les champs principaux."; min=60; must='(?i)r.le'; mustNot="^\d+ table\(s\)|$EVENT_FIELD_ONLY"; struct=$true; srcAny=$true } )
C "TOPIC" "TOPIC-AC-CHARGE" "AC" @( @{ q="quelles tables contiennent des informations sur les charges appliquees a un compte ? Pour chacune, precise son role et ses champs principaux."; min=60; must='(?i)r.le'; mustNot='^\d+ table\(s\)'; struct=$true; srcAny=$true } )

# 6) Listes
C "LIST" "LIST-LD-TABLES" "LD" @( @{ q="liste les tables du module LD"; min=20; must='LD\.LOANS\.AND\.DEPOSITS|LD\.COMMITMENT|BILL\.REGISTER' } )
C "LIST" "LIST-TT-CHAMPS" "TT" @( @{ q="quels sont les champs de la table tt.teller.hp.versions ?"; min=60; must='TT\.HP\.VERSION|TT\.GEN\.VERSION'; srcOnly='^TT\.TELLER\.HP\.VERSIONS$'; struct=$true } )
C "LIST" "LIST-AC-CHAMPS" "AC" @( @{ q="quels sont les principaux champs de la table account ?"; min=80; srcOnly='^ACCOUNT'; struct=$true } )

# 7) Informations inexistantes
C "NOTFOUND" "NF-AC-CHAMP" "AC" @( @{ q="il y a un champ couleur dans account ?"; must='(?i)aucun|n.y a pas|introuvable|pas de champ'; srcNone=$true; max=60 } )
C "NOTFOUND" "NF-LD-TABLE" "LD" @( @{ q="quels sont les champs de la table ld.customer.history ?"; must='(?i)aucune|introuvable|pas'; srcNone=$true; max=60 } )
C "NOTFOUND" "NF-LC-CHAMP" "LC" @( @{ q="explique le champ score.risque dans lc"; must='(?i)aucune|introuvable|pas'; srcNone=$true; max=60 } )
C "NOTFOUND" "NF-MD" "MD" @( @{ q="md.beneficiary contient un champ telephone ?"; must='(?i)aucun|n.y a pas|introuvable|pas de champ|ne contient pas|n.existe pas'; max=90 } )

# 8) Ambigu / comparatif
C "AMBIG" "AMBIG-LD" "LD" @( @{ q="quel est le montant du pret ?"; must='(?i)pr.cis|plusieurs|lequel|pouvez-vous'; mustNot=$EVENT_FIELD_ONLY } )
C "COMPARE" "CMP-LD" "LD" @( @{ q="quelle est la difference entre ld.commitment et ld.loans.and.deposits ?"; min=60; must='LD\.COMMITMENT'; struct=$false } )

# 9) Suivis conversationnels sur un champ et sur une table
C "FOLLOW" "FOLLOW-AC-CHAMP" "AC" @(
  @{ q="a quoi sert le champ currency dans account ?"; min=40; must='CURRENCY'; srcOnly='^ACCOUNT' },
  @{ q="donne moi un exemple"; min=25; noRepeat=$true; srcOnly='^ACCOUNT' },
  @{ q="et ses valeurs autorisees ?"; min=25; must='(?i)SSS|3 caract|1 . 3|code|devise'; srcOnly='^ACCOUNT'; noRepeat=$true },
  @{ q="explique davantage"; min=40; noRepeat=$true; srcOnly='^ACCOUNT' }
)
C "FOLLOW" "FOLLOW-TT-TABLE" "TT" @(
  @{ q="explique la table tt.teller.hp.versions"; min=50; must='TT\.TELLER\.HP\.VERSIONS' },
  @{ q="et ses champs ?"; min=40; must='TT\.(HP|GEN|AUTH)\.VERSION'; srcOnly='^TT\.TELLER\.HP\.VERSIONS$'; noRepeat=$true },
  @{ q="detaille le deuxieme"; min=30; must='TT\.(HP|GEN|AUTH)\.VERSION|OVERRIDE'; noRepeat=$true }
)
C "FOLLOW" "FOLLOW-EB" "EB" @(
  @{ q="a quoi sert la table eb.free.message ?"; min=40; must='EB\.FREE\.MESSAGE' },
  @{ q="peux-tu detailler le champ narrative ?"; min=40; must='NARRATIVE'; srcOnly='^EB\.FREE\.MESSAGE$'; noRepeat=$true },
  @{ q="et quelles sont ses regles de validation ?"; min=20; must='(?i)swift|50|35|lignes|caract'; srcOnly='^EB\.FREE\.MESSAGE$'; noRepeat=$true }
)

# 10) Question simple : reponse concise
C "SIMPLE" "SIMPLE-AC" "AC" @( @{ q="le champ currency dans account est-il obligatoire ?"; max=110; must='(?i)oui|obligatoire'; srcOnly='^ACCOUNT' } )

# ---- Execution ---------------------------------------------------------------------------------------
$results = @(); $pass = 0; $fail = 0; $byCat = @{}; $times = @()
foreach ($c in $T) {
    if ($Filter -and $c.id -notmatch $Filter) { continue }
    $cid = $null; $prev = @()
    $i = 0
    foreach ($t in $c.turns) {
        $i++
        $t.q = [string]$t.q
        $resp = Ask $t.q $c.mod $cid
        if ($null -eq $resp) { $f = @("appel en echec"); $ans = ""; $tables = @(); $secs = 0 }
        else {
            $cid = $resp.conversationId
            $f = Check $t $resp $prev $c.mod
            $ans = $resp.answer; $secs = $resp.secs; $times += $secs
            $tables = @($resp.sourceFields | ForEach-Object { $_.tableName } | Select-Object -Unique)
            $prev += $ans
        }
        $ok = ($f.Count -eq 0)
        if ($ok) { $pass++ } else { $fail++ }
        if (-not $byCat.ContainsKey($c.cat)) { $byCat[$c.cat] = @(0,0) }
        if ($ok) { $byCat[$c.cat][0]++ } else { $byCat[$c.cat][1]++ }
        $status = if ($ok) { "PASS" } else { "FAIL" }
        $wc = ($ans -split '\s+' | Where-Object { $_ }).Count
        Write-Host "[$status] $($c.id)#$i [$wc mots, ${secs}s] $($f -join '; ')"
        $results += [pscustomobject]@{ id=$c.id; cat=$c.cat; module=$c.mod; turn=$i; question=$t.q; pass=$ok; failures=$f; words=$wc; secs=$secs; sources=$tables; answer=$ans }
    }
}
$avg = if ($times.Count) { [int](($times | Measure-Object -Average).Average) } else { 0 }
$summary = @("=== BILAN [$Label] : $pass PASS / $fail FAIL sur $($pass+$fail) tours ; temps moyen ${avg}s")
foreach ($k in ($byCat.Keys | Sort-Object)) { $summary += ("  {0,-9} {1} PASS / {2} FAIL" -f $k, $byCat[$k][0], $byCat[$k][1]) }
$summary | ForEach-Object { Write-Host $_ }
$results | ConvertTo-Json -Depth 5 | Out-File "$root\results-$Label.json" -Encoding utf8
($summary + "" + ($results | ForEach-Object { "[{0}] {1}#{2} : {3}`n   {4}`n" -f ($(if ($_.pass) {"PASS"} else {"FAIL"})), $_.id, $_.turn, ($_.failures -join '; '), ($_.answer -replace '\s+',' ') })) | Out-File "$root\report-$Label.txt" -Encoding utf8
