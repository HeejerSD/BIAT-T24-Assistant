# BIAT IT — T24 Assistant · Projet complet

Projet composé d'un **frontend Angular 17** (site web) et d'un **backend Spring Boot 3.2** (API REST).

---

## Structure du projet

```
biat-t24-project/
├── frontend/          ← Angular 17 (site web, port 4200)
└── backend/           ← Spring Boot 3.2 (API REST, port 8080)
    └── src/main/resources/data/
        ├── helper.txt           ← vos descriptions de colonnes
        └── xml/                 ← vos fichiers XML T24
            ├── LD.LOANS.MASTER.xml
            └── AC.ACCOUNT.MASTER.xml
```

---

## Téléchargements requis

### 1. Java 17+
→ https://adoptium.net/temurin/releases/?version=17
Choisir : Windows x64 .msi (ou macOS .pkg)

### 2. Maven 3.9+
→ https://maven.apache.org/download.cgi
Télécharger : apache-maven-3.9.x-bin.zip
Extraire et ajouter au PATH

### 3. Node.js 20 LTS
→ https://nodejs.org/en/download
Choisir : LTS · Windows Installer (.msi)

### 4. Angular CLI
```bash
npm install -g @angular/cli
```

### 5. VS Code (recommandé)
→ https://code.visualstudio.com/

---

## Lancer le microservice FAISS (embeddings + recherche vectorielle)

**Indispensable** pour que le chat réponde aux questions qui ne citent pas le nom exact d'une colonne
(ex. « quelle colonne permet de calculer le taux d'intérêt d'un prêt ? »). Sans ce service, seules les
questions qui mentionnent le nom exact d'une colonne (ex. `CUSTOMER.ID`) fonctionnent, car elles passent
par une recherche exacte en base qui ne nécessite pas d'embeddings.

```bash
cd faiss-service

# Première fois seulement : créer le venv et installer les dépendances
python -m venv venv
venv\Scripts\activate          # Windows
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt

# Lancer le service (à chaque démarrage, AVANT le backend)
venv\Scripts\python.exe -m uvicorn main:app --port 8001
```

**Le service tourne sur http://localhost:8001** — vérifiez avec `GET http://localhost:8001/health`.

Si ce service était indisponible lors d'un import (`/api/chat/reindex`), les colonnes concernées sont
enregistrées sans vecteur d'embedding. Relancer `/api/chat/reindex` une fois le service démarré rattrape
automatiquement les colonnes dont l'embedding manque encore, sans tout réindexer.

---

## Lancer le backend (Spring Boot)

```bash
# 1. Aller dans le dossier backend
cd biat-t24-project/backend

# 2. Définir votre clé API Claude (Anthropic)
# Windows PowerShell :
$env:AI_API_KEY="sk-ant-api03-votre-cle-ici"
# Mac/Linux :
export AI_API_KEY="sk-ant-api03-votre-cle-ici"

# 3. Lancer
mvn spring-boot:run
```

**Le backend tourne sur http://localhost:8080/api**

Vous verrez dans la console :
```
Started T24AssistantApplication on port 8080
Helper indexé : 17 entrées
2 tables T24 indexées
```

---

## Lancer le frontend (Angular)

```bash
# Dans un NOUVEAU terminal
cd biat-t24-project/frontend

# Installer les dépendances (première fois seulement)
npm install

# Lancer
ng serve
```

**Le site ouvre automatiquement sur http://localhost:4200**

---

## Tester que tout fonctionne

### Test backend (Postman ou navigateur) :
```
GET  http://localhost:8080/api/chat/health
GET  http://localhost:8080/api/chat/modules
GET  http://localhost:8080/api/chat/tables

POST http://localhost:8080/api/chat
Body JSON :
{
  "question": "À quoi sert CUSTOMER.ID dans LD.LOANS.MASTER ?",
  "module": "LD",
  "tableName": "LD.LOANS.MASTER"
}
```

### Test frontend :
Ouvrir http://localhost:4200
- Page d'accueil → site web avec hero + fonctionnalités + modules
- Bouton "Ouvrir l'assistant" → page chat

---

## Ajouter vos vrais fichiers T24

Remplacez les fichiers d'exemple par vos vrais fichiers :

```
backend/src/main/resources/data/
├── helper.txt        ← votre helper.txt réel
└── xml/
    ├── LD.LOANS.MASTER.xml
    ├── LD.LOANS.SCHEDULES.xml
    └── ...            ← tous vos XML T24
```

Redémarrez le backend — il les indexe automatiquement.

---

## Obtenir une clé API Claude

1. Aller sur https://console.anthropic.com
2. Créer un compte / se connecter
3. API Keys → Create Key
4. Copier la clé (commence par `sk-ant-...`)
5. La mettre dans la variable AI_API_KEY avant de lancer le backend

---

*BIAT Innovation & Technology — Projet de stage T24 AI Assistant*
