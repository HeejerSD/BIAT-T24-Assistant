import { Component, OnInit, OnDestroy, ViewChild, ElementRef, AfterViewChecked, HostListener } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, switchMap } from 'rxjs';
import { ChatService, ModuleMatch, ConversationSummary, T24Table, SourceField } from '../../services/chat.service';
import { AuthService } from '../../services/auth.service';
import { renderMarkdown } from './markdown.util';

/** Champs d'une même table regroupés sous un seul en-tête "Table :" dans la carte "Voir la source" (voir
 *  groupSourceFields) — plusieurs champs cités d'une même table ne répètent plus la table à chaque fois. */
interface SourceGroup {
  tableName: string;
  module?: string | null;
  xmlFile?: string | null;
  fields: SourceField[];
}

interface Message {
  role: 'user' | 'bot';
  content: string;
  status?: string;
  loading?: boolean;
  moduleChoices?: string[];
  tableChoices?: T24Table[];
  /** Tables/colonnes sources de cette réponse, déjà décidées par le backend selon ce que la question demandait
   *  (voir SourceFieldDto) : présentes soit en direct (événement "done" du flux), soit restaurées depuis la base
   *  au rechargement de la conversation. */
  sourceFields?: SourceField[];
  /** Regroupement de sourceFields par table, calculé une fois à la réception (voir groupSourceFields) plutôt
   *  qu'à chaque cycle de détection de changement dans le template. */
  sourceGroups?: SourceGroup[];
  /** Affichage de la carte "Voir la source" (repliée par défaut, dépliée au clic sur le bouton) : voir toggleSourceCard. */
  sourceCardOpen?: boolean;
}

/** Regroupe des sources par table, en conservant l'ordre de première apparition (table puis champs) : chaque
 *  champ garde ses propres mandatoryLabel/description/validationRule (peuvent différer d'un champ à l'autre de
 *  la même table), seul l'en-tête "Table :" n'est affiché qu'une fois par table. */
function groupSourceFields(fields: SourceField[] | undefined): SourceGroup[] | undefined {
  if (!fields || fields.length === 0) return undefined;
  const groups: SourceGroup[] = [];
  const byTable = new Map<string, SourceGroup>();
  for (const f of fields) {
    let group = byTable.get(f.tableName);
    if (!group) {
      group = { tableName: f.tableName, module: f.module, xmlFile: f.xmlFile, fields: [] };
      byTable.set(f.tableName, group);
      groups.push(group);
    }
    group.fields.push(f);
  }
  return groups;
}

/** Étape du dialogue guidé : d'abord le module, puis directement la question libre (pas d'étape "table" imposée). */
type ChatStage = 'ask-module' | 'ready';

/** Préfixe du message bot généré quand aucun module n'a été reconnu, utilisé pour retrouver l'étape d'une conversation restaurée. */
const NO_MODULE_MATCH_PREFIX = "Je n'ai pas trouvé de module correspondant";

/** Retrouve le code module (ex. "LD") dans le message-modèle exact "Vous voulez parler du module « LD ». Comment
 *  puis-je vous aider ?" généré par resolveToModule — ancré en tout début de message pour ne JAMAIS matcher une
 *  réponse en prose du LLM qui mentionnerait aussi "le module « Letters of Credit »" (nom complet, pas le code) :
 *  observé en pratique, ce faux positif faisait repartir les questions suivantes avec le libellé complet au lieu
 *  du code, et la recherche en base par code de module ne trouvait alors plus rien ("Aucune table trouvée"). */
const MODULE_CODE_PATTERN = /^Vous voulez parler du module\s*«\s*([^»]+?)\s*»/;

/** Modules T24 sur lesquels le chat est restreint (voir aussi ActiveModules côté backend), proposés en bulles
 *  dès le message d'accueil. Les autres modules restent en base mais ne sont jamais proposés ici. */
const COMMON_MODULE_CODES = ['AC', 'LC', 'TT', 'LD', 'EB', 'DE', 'MD'];

@Component({
  selector: 'app-chat',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './chat.component.html',
  styleUrls: ['./chat.component.scss']
})
export class ChatComponent implements OnInit, AfterViewChecked, OnDestroy {
  @ViewChild('messagesEnd') private messagesEnd!: ElementRef;

  question = '';
  selectedModule = '';
  selectedTable = '';
  messages: Message[] = [];
  loading = false;
  stage: ChatStage = 'ask-module';
  username = '';
  isSuperAdmin = false;
  copiedMessageIndex: number | null = null;
  editingIndex: number | null = null;
  editingContent = '';

  /** Déplie/replie la carte "Voir la source" d'un message, directement sous la réponse (pas un panneau séparé). */
  toggleSourceCard(msg: Message) {
    msg.sourceCardOpen = !msg.sourceCardOpen;
  }

  /** Barre latérale (historique des conversations) en recouvrement sur mobile (voir media query <=768px) :
   *  fermée par défaut pour ne jamais repousser la zone de chat hors de l'écran visible (bug observé quand
   *  l'historique était long). Sans effet visuel au-delà de 768px (la classe CSS associée n'y fait rien). */
  mobileSidebarOpen = false;

  toggleMobileSidebar() {
    this.mobileSidebarOpen = !this.mobileSidebarOpen;
  }

  conversations: ConversationSummary[] = [];
  currentConversationId: number | null = null;
  historySearch = '';
  historyLoading = false;
  pinnedCollapsed = false;
  openMenuId: number | null = null;
  private historySearch$ = new Subject<string>();

  get pinnedConversations(): ConversationSummary[] {
    return this.conversations.filter(c => c.pinned);
  }

  get recentConversations(): ConversationSummary[] {
    return this.conversations.filter(c => !c.pinned);
  }

  get inputPlaceholder(): string {
    if (this.stage === 'ask-module') return 'Ex : comptes, prêts, virements, dépôts...';
    return 'Ex : je veux savoir à quoi sert le montant dû dans les échéances de prêt';
  }

  @HostListener('document:click')
  closeMenu() {
    this.openMenuId = null;
  }

  toggleMenu(conv: ConversationSummary, event: Event) {
    event.stopPropagation();
    this.openMenuId = this.openMenuId === conv.id ? null : conv.id;
  }

  /** Rendu Markdown sûr (titres, gras, listes, tableaux) du texte d'une réponse de l'assistant. */
  renderMarkdown = renderMarkdown;

  constructor(
    private chatService: ChatService,
    private route: ActivatedRoute,
    private authService: AuthService
  ) {}

  ngOnInit() {
    this.username = this.authService.getUsername() || 'Moi';
    this.isSuperAdmin = this.authService.isSuperAdmin();
    // Une navigation avec module/table en query params (ex. bouton "Poser une question sur cette table" de la
    // page de détail d'une table) vise TOUJOURS un contexte précis et NOUVEAU : dans ce cas, on ne doit jamais
    // reprendre la dernière conversation en cours (potentiellement sur un tout autre module/table), sinon le
    // badge de contexte en haut de page (à jour) et les messages affichés (ceux de l'ancienne conversation) se
    // contredisent (observé en pratique : badge "AA.AGENT.COMMISSION.ONLINE.WORK", messages parlant de
    // MD.BENEFICIARY). Une simple ouverture de /chat SANS query params (FAB, menu, page d'accueil) continue de
    // restaurer la dernière conversation, pour retrouver le fil en cours après un rafraîchissement de page.
    let navigatedWithNewContext = false;
    this.route.queryParams.subscribe(p => {
      if (p['module']) { this.selectedModule = p['module']; navigatedWithNewContext = true; }
      if (p['table']) { this.selectedTable = p['table']; navigatedWithNewContext = true; }
    });
    this.resetConversation();
    this.loadConversations();
    if (navigatedWithNewContext) {
      this.setCurrentConversation(null);
    } else {
      this.restoreLastConversation();
    }

    this.historySearch$.pipe(
      debounceTime(300),
      distinctUntilChanged(),
      switchMap(q => q.trim() ? this.chatService.searchConversations(q.trim()) : this.chatService.listConversations())
    ).subscribe({
      next: list => { this.conversations = list; this.historyLoading = false; },
      error: () => { this.historyLoading = false; }
    });
  }

  ngAfterViewChecked() {
    this.scrollToBottom();
  }

  ngOnDestroy() {
    this.historySearch$.complete();
  }

  send() {
    const q = this.question.trim();
    if (!q || this.loading) return;
    this.question = '';
    this.dispatch(q);
  }

  /** Route la saisie utilisateur selon l'étape courante du dialogue guidé. */
  private dispatch(q: string) {
    if (this.stage === 'ask-module') {
      this.handleModuleQuery(q);
      return;
    }
    this.sendQuestion(q);
  }

  /** Étape 1 du dialogue guidé : interprète en langage naturel le module demandé ("account", "les prêts"...). */
  private handleModuleQuery(q: string) {
    this.messages.push({ role: 'user', content: q });
    this.messages.push({ role: 'bot', content: '', loading: true });
    this.loading = true;
    const idx = this.messages.length - 1;

    this.chatService.resolveModule(q).subscribe({
      next: (matches: ModuleMatch[]) => {
        const best = matches[0];
        if (!best) {
          const content = `Je n'ai pas trouvé de module correspondant à "${q}". Pouvez-vous préciser (par exemple : comptes, prêts, virements, dépôts...) ?`;
          this.messages[idx] = { role: 'bot', content, loading: false };
          this.loading = false;
          this.persistStep(q, content);
          return;
        }
        this.resolveToModule(q, best.code, idx);
      },
      error: () => {
        this.messages[idx] = { role: 'bot', content: 'Erreur de connexion au backend.', loading: false };
        this.loading = false;
      }
    });
  }

  /** Raccourci : l'utilisateur clique directement une bulle de module courant, pas besoin de passer par le LLM. */
  selectModuleChoice(code: string) {
    if (this.stage !== 'ask-module') return;
    this.messages.push({ role: 'user', content: code });
    this.messages.push({ role: 'bot', content: '', loading: true });
    this.loading = true;
    this.resolveToModule(code, code, this.messages.length - 1);
  }

  /** Un module vient d'être résolu (LLM ou clic direct) : on peut directement commencer à interroger, sans étape "table". */
  private resolveToModule(userMessage: string, code: string, botLoadingIdx: number) {
    this.selectedModule = code;
    this.stage = 'ready';
    const botMessage = `Vous voulez parler du module « ${code} ». Comment puis-je vous aider ?`;
    this.messages[botLoadingIdx] = { role: 'bot', content: botMessage, loading: false };
    this.loading = false;
    this.persistStep(userMessage, botMessage);
  }

  /** L'utilisateur clique une bulle de table proposée après une demande "liste les tables" : la sélectionne pour affiner ses prochaines questions. */
  selectTableFromChat(table: T24Table) {
    this.selectedTable = table.name;
    if (table.module) this.selectedModule = table.module;
    const botMessage = `Table « ${table.name} » sélectionnée. Comment puis-je vous aider ?`;
    this.messages.push({ role: 'user', content: table.name });
    this.messages.push({ role: 'bot', content: botMessage });
    this.persistStep(table.name, botMessage);
  }

  /** Enregistre un échange du dialogue guidé (résolution de module) dans l'historique en base, comme les questions/réponses classiques. */
  private persistStep(userMessage: string, botMessage: string) {
    this.chatService.step({
      conversationId: this.currentConversationId,
      userMessage,
      botMessage
    }).subscribe({
      next: res => {
        this.setCurrentConversation(res.conversationId);
        this.loadConversations();
      },
      error: () => {}
    });
  }

  /** Le texte de la réponse s'affiche au fil de l'eau (fragment par fragment) plutôt que d'attendre la réponse
   *  complète : sur un LLM local sans GPU, l'attente peut durer plusieurs secondes, autant montrer la progression. */
  private sendQuestion(q: string) {
    this.messages.push({ role: 'user', content: q });
    const botMessage: Message = { role: 'bot', content: '', loading: true };
    this.messages.push(botMessage);
    this.loading = true;

    this.chatService.askStream(
      {
        question: q,
        module: this.selectedModule || undefined,
        tableName: this.selectedTable || undefined,
        conversationId: this.currentConversationId
      },
      text => {
        botMessage.loading = false;
        botMessage.content += text;
      },
      meta => {
        botMessage.loading = false;
        botMessage.status = meta.status;
        botMessage.tableChoices = meta.tables && meta.tables.length > 0 ? meta.tables : undefined;
        botMessage.sourceFields = meta.sourceFields && meta.sourceFields.length > 0 ? meta.sourceFields : undefined;
        botMessage.sourceGroups = groupSourceFields(botMessage.sourceFields);
        // La question nommait explicitement un AUTRE module que celui sélectionné (ex. "présente-moi le module EB"
        // alors que selectedModule valait encore "MD") : le backend a répondu sur ce nouveau module, on aligne la
        // sélection locale pour que les questions suivantes (sans forcément renommer le module) restent sur lui.
        if (meta.resolvedModule) {
          this.selectedModule = meta.resolvedModule;
          this.selectedTable = '';
        }
        this.loading = false;
        this.setCurrentConversation(meta.conversationId);
        this.loadConversations();
      },
      () => {
        botMessage.loading = false;
        if (!botMessage.content) {
          botMessage.content = 'Erreur de connexion au backend. Vérifiez que Spring Boot tourne sur le port 8080.';
        }
        this.loading = false;
      }
    );
  }

  /**
   * Détermine à quelle étape reprendre une conversation restaurée depuis l'historique : l'historique en base
   * ne stocke que du texte brut, donc on retrouve l'étape à partir des messages-modèles du dialogue guidé.
   */
  private inferStageFromMessages(msgs: Message[]): ChatStage {
    const lastBot = [...msgs].reverse().find(m => m.role === 'bot');
    if (!lastBot) return 'ask-module';
    if (lastBot.content.startsWith(NO_MODULE_MATCH_PREFIX)) return 'ask-module';
    return 'ready';
  }

  /**
   * Une conversation restaurée depuis l'historique ne stocke que du texte brut (pas selectedModule) : sans ça,
   * les questions suivantes repartiraient sans contexte de module. On retrouve le code dans le dernier message
   * bot qui en mentionne un (ex. "Vous voulez parler du module « LD »...").
   */
  private restoreModuleFromMessages(msgs: Message[]) {
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role !== 'bot') continue;
      const match = MODULE_CODE_PATTERN.exec(msgs[i].content);
      if (match) {
        this.selectedModule = match[1].trim();
        return;
      }
    }
  }

  clearChat() {
    this.newConversation();
  }

  /** Démarre une nouvelle conversation vierge : le dialogue guidé recommence depuis le choix du module. */
  newConversation() {
    this.mobileSidebarOpen = false;
    this.setCurrentConversation(null);
    this.selectedModule = '';
    this.selectedTable = '';
    this.resetConversation();
  }

  private resetConversation() {
    if (this.selectedModule) {
      // Contexte déjà connu (ex. navigation depuis la liste des modules) : pas besoin de le redemander.
      this.stage = 'ready';
      // Formulation distincte selon qu'une TABLE précise est déjà sélectionnée (ex. bouton "Poser une question sur
      // cette table" depuis sa page de détail) ou seulement un module : "vous consultez le module AA · X.Y.Z"
      // laissait croire, à tort, que X.Y.Z faisait partie du nom du module (observé en pratique, confusion réelle).
      const subject = this.selectedTable
          ? `la table ${this.selectedTable} (module ${this.selectedModule})`
          : `le module ${this.selectedModule}`;
      this.messages = [{ role: 'bot', content: `Bonjour ! Vous consultez exactement ${subject}. Comment puis-je vous aider ?` }];
      return;
    }
    this.stage = 'ask-module';
    this.messages = [{
      role: 'bot',
      content: 'Bonjour ! Je suis l\'assistant T24 BIAT IT. Quel module souhaitez-vous interroger ? Choisissez un module courant ci-dessous, ou décrivez-le avec vos propres mots (par exemple : comptes, prêts, virements, dépôts...).',
      moduleChoices: COMMON_MODULE_CODES
    }];
  }

  /** Clé localStorage scopée par utilisateur, pour ne pas mélanger la conversation active de deux comptes sur le même navigateur. */
  private currentConversationStorageKey(): string {
    return `biat_current_conversation_${this.username}`;
  }

  private setCurrentConversation(id: number | null) {
    this.currentConversationId = id;
    if (id != null) localStorage.setItem(this.currentConversationStorageKey(), String(id));
    else localStorage.removeItem(this.currentConversationStorageKey());
  }

  /** Recharge la conversation active laissée en cours lors du dernier passage (persistance au rafraîchissement de la page). */
  private restoreLastConversation() {
    const stored = localStorage.getItem(this.currentConversationStorageKey());
    if (!stored) return;
    const id = Number(stored);
    if (!Number.isFinite(id)) return;
    this.chatService.getMessages(id).subscribe({
      next: msgs => {
        if (msgs.length === 0) return;
        this.currentConversationId = id;
        this.messages = msgs.map((m): Message => {
          const sourceFields = m.sourceFields && m.sourceFields.length > 0 ? m.sourceFields : undefined;
          return {
            role: m.messageType === 'question' ? 'user' : 'bot',
            content: m.content,
            sourceFields,
            sourceGroups: groupSourceFields(sourceFields)
          };
        });
        this.stage = this.inferStageFromMessages(this.messages);
        this.restoreModuleFromMessages(this.messages);
      },
      error: () => localStorage.removeItem(this.currentConversationStorageKey())
    });
  }

  private loadConversations() {
    this.chatService.listConversations().subscribe({
      next: list => this.conversations = list,
      error: () => {}
    });
  }

  onHistorySearchChange() {
    this.historyLoading = true;
    this.historySearch$.next(this.historySearch);
  }

  selectConversation(conv: ConversationSummary) {
    if (this.loading || conv.id === this.currentConversationId) return;
    this.mobileSidebarOpen = false;
    this.setCurrentConversation(conv.id);
    this.selectedModule = '';
    this.selectedTable = '';
    this.chatService.getMessages(conv.id).subscribe({
      next: msgs => {
        this.messages = msgs.map((m): Message => {
          const sourceFields = m.sourceFields && m.sourceFields.length > 0 ? m.sourceFields : undefined;
          return {
            role: m.messageType === 'question' ? 'user' : 'bot',
            content: m.content,
            sourceFields,
            sourceGroups: groupSourceFields(sourceFields)
          };
        });
        if (this.messages.length === 0) this.resetConversation();
        else {
          this.stage = this.inferStageFromMessages(this.messages);
          this.restoreModuleFromMessages(this.messages);
        }
      },
      error: () => this.resetConversation()
    });
  }

  deleteConversation(conv: ConversationSummary, event: Event) {
    event.stopPropagation();
    this.chatService.deleteConversation(conv.id).subscribe({
      next: () => {
        this.conversations = this.conversations.filter(c => c.id !== conv.id);
        if (this.currentConversationId === conv.id) this.newConversation();
      }
    });
  }

  togglePin(conv: ConversationSummary, event: Event) {
    event.stopPropagation();
    const request = conv.pinned ? this.chatService.unpinConversation(conv.id) : this.chatService.pinConversation(conv.id);
    request.subscribe({
      next: () => this.loadConversations()
    });
  }

  /** Un utilisateur (autre que le superAdmin) ne peut agir que sur ses propres conversations. */
  isOwnConversation(conv: ConversationSummary): boolean {
    return conv.username === this.username;
  }

  copyMessage(content: string, index: number) {
    const onCopied = () => {
      this.copiedMessageIndex = index;
      setTimeout(() => {
        if (this.copiedMessageIndex === index) this.copiedMessageIndex = null;
      }, 1500);
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(content).then(onCopied, () => this.copyWithFallback(content, onCopied));
    } else {
      this.copyWithFallback(content, onCopied);
    }
  }

  /** Repli pour les contextes non sécurisés (HTTP hors localhost) où l'API Clipboard n'existe pas. */
  private copyWithFallback(content: string, onCopied: () => void) {
    const area = document.createElement('textarea');
    area.value = content;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    try {
      if (document.execCommand('copy')) onCopied();
    } finally {
      document.body.removeChild(area);
    }
  }

  /** Passe le message en édition inline (comme ChatGPT) : la bulle devient une zone de texte modifiable. */
  editMessage(index: number, content: string) {
    this.editingIndex = index;
    this.editingContent = content;
  }

  cancelEdit() {
    this.editingIndex = null;
    this.editingContent = '';
  }

  /** Valide l'édition : remplace le message (et tout ce qui suivait) puis renvoie la question modifiée. */
  submitEdit(index: number) {
    const q = this.editingContent.trim();
    if (!q || this.loading) return;
    this.messages = this.messages.slice(0, index);
    this.editingIndex = null;
    this.editingContent = '';
    this.stage = this.inferStageAfterTruncate();
    this.dispatch(q);
  }

  /** Devine à quelle étape du dialogue guidé on se trouve après avoir tronqué l'historique pour une édition. */
  private inferStageAfterTruncate(): ChatStage {
    if (this.messages.length <= 1 && !this.selectedModule) return 'ask-module';
    return 'ready';
  }

  onEditKeydown(e: KeyboardEvent, index: number) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.submitEdit(index); }
    if (e.key === 'Escape') { e.preventDefault(); this.cancelEdit(); }
  }

  formatConversationDate(iso: string): string {
    const date = new Date(iso);
    const now = new Date();
    const isSameDay = date.toDateString() === now.toDateString();
    if (isSameDay) return date.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
    const yesterday = new Date(now);
    yesterday.setDate(now.getDate() - 1);
    if (date.toDateString() === yesterday.toDateString()) return 'Hier';
    return date.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
  }

  onKeydown(e: KeyboardEvent) {
    if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); this.send(); }
  }

  private scrollToBottom() {
    try { this.messagesEnd.nativeElement.scrollIntoView({ behavior: 'smooth' }); } catch {}
  }
}
