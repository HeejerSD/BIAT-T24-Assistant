import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';

export interface ChatRequest {
  question: string;
  module?: string;
  tableName?: string;
  conversationHistory?: { role: string; content: string }[];
  conversationId?: number | null;
}

export interface ConversationSummary {
  id: number;
  title: string;
  updatedAt: string;
  pinned: boolean;
  username: string;
}

/** Table/colonne source d'une réponse, pour la carte "Voir la source" — correspond à SourceFieldDto côté backend.
 *  columnName est absent pour une question d'identification de table (voir buildTableOnlySourceFields côté
 *  backend) : la carte montre alors juste la table, sans laisser croire qu'un champ précis est "la réponse".
 *  mandatoryLabel/description/validationRule sont absents quand la question n'a pas porté sur cet aspect précis. */
export interface SourceField {
  tableName: string;
  columnName: string | null;
  mandatoryLabel?: string | null;
  description?: string | null;
  validationRule?: string | null;
  module?: string | null;
  xmlFile?: string | null;
}

export interface StoredChatMessage {
  messageType: string;
  content: string;
  createdAt: string;
  sourceFields?: SourceField[];
}

export interface ColumnResult {
  columnName: string;
  tableName: string;
  module: string;
  dataType: string;
  inputType: string;
  mandatory: boolean;
  description: string;
  standardSupported: boolean;
  helperLineNumber?: number;
}

export interface ChatResponse {
  answer: string;
  columns: ColumnResult[];
  status: string;
  confidence: number;
  timestamp: string;
  conversationId: number;
  tables: T24Table[];
  /** Non nul uniquement quand la question nommait explicitement un AUTRE module que celui sélectionné : le
   *  composant chat met alors à jour sa propre sélection pour que les questions suivantes restent sur ce module. */
  resolvedModule?: string | null;
}

export interface T24Table {
  name: string;
  module: string;
  description: string;
  columnCount: number;
}

export interface ColumnDescription {
  columnName: string;
  description: string;
}

export interface ModuleSummary {
  code: string;
  label: string | null;
  tableCount: number;
}

export interface ModuleMatch {
  code: string;
  label: string | null;
  score: number;
}

export interface ChatStepRequest {
  conversationId: number | null;
  userMessage: string;
  botMessage: string;
}

export interface ChatStreamDone {
  columns: ColumnResult[];
  tables: T24Table[];
  conversationId: number;
  confidence: number;
  status: string;
  sourceFields: SourceField[];
  /** Voir ChatResponse#resolvedModule. */
  resolvedModule?: string | null;
}

@Injectable({ providedIn: 'root' })
export class ChatService {
  private api = environment.apiUrl;

  constructor(private http: HttpClient, private authService: AuthService) {}

  ask(request: ChatRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.api}/chat`, request);
  }

  /**
   * Version "streaming" de {@link ask} : le texte de la réponse arrive fragment par fragment (affichage progressif),
   * via un flux SSE. Angular HttpClient ne gère pas nativement les flux SSE en POST avec en-têtes, et l'API
   * navigateur EventSource ne supporte ni POST ni en-têtes personnalisés : on utilise donc `fetch` directement,
   * en attachant le token JWT à la main (l'intercepteur HTTP d'Angular ne s'applique pas ici).
   */
  async askStream(
    request: ChatRequest,
    onChunk: (text: string) => void,
    onDone: (meta: ChatStreamDone) => void,
    onError: (err: unknown) => void
  ): Promise<void> {
    try {
      const token = this.authService.getToken();
      const response = await fetch(`${this.api}/chat/stream`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {})
        },
        body: JSON.stringify(request)
      });
      if (!response.ok || !response.body) {
        onError(new Error(`HTTP ${response.status}`));
        return;
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let sepIndex: number;
        while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
          const rawEvent = buffer.slice(0, sepIndex);
          buffer = buffer.slice(sepIndex + 2);
          this.parseSseEvent(rawEvent, onChunk, onDone);
        }
      }
    } catch (err) {
      onError(err);
    }
  }

  private parseSseEvent(rawEvent: string, onChunk: (text: string) => void, onDone: (meta: ChatStreamDone) => void) {
    let eventName = 'message';
    const dataLines: string[] = [];
    for (const line of rawEvent.split('\n')) {
      if (line.startsWith('event:')) eventName = line.slice(6).trim();
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
    }
    if (dataLines.length === 0) return;
    const data = JSON.parse(dataLines.join('\n'));
    if (eventName === 'chunk') onChunk(data.text);
    else if (eventName === 'done') onDone(data as ChatStreamDone);
  }

  /** Persiste un échange du dialogue guidé (choix de module/table) sans passer par la recherche/LLM. */
  step(request: ChatStepRequest): Observable<ChatResponse> {
    return this.http.post<ChatResponse>(`${this.api}/chat/step`, request);
  }

  getModules(): Observable<ModuleSummary[]> {
    return this.http.get<ModuleSummary[]>(`${this.api}/chat/modules`);
  }

  /** Résout un texte libre ("account", "les prêts"...) vers les modules T24 dont le libellé est le plus proche. */
  resolveModule(q: string): Observable<ModuleMatch[]> {
    return this.http.get<ModuleMatch[]>(`${this.api}/chat/modules/resolve`, { params: { q } });
  }

  getTables(): Observable<T24Table[]> {
    return this.http.get<T24Table[]>(`${this.api}/chat/tables`);
  }

  getTablesByModule(module: string): Observable<T24Table[]> {
    return this.http.get<T24Table[]>(`${this.api}/chat/tables/module/${module}`);
  }

  /** Colonnes et leur description pour une table T24 précise (page de détail d'une table). */
  getTableColumnDescriptions(tableName: string): Observable<ColumnDescription[]> {
    return this.http.get<ColumnDescription[]>(`${this.api}/chat/tables/${tableName}/columns`);
  }

  getHealth(): Observable<any> {
    return this.http.get(`${this.api}/chat/health`);
  }

  reindex(): Observable<any> {
    return this.http.post(`${this.api}/chat/reindex`, {});
  }

  listConversations(): Observable<ConversationSummary[]> {
    return this.http.get<ConversationSummary[]>(`${this.api}/chat/conversations`);
  }

  searchConversations(q: string): Observable<ConversationSummary[]> {
    return this.http.get<ConversationSummary[]>(`${this.api}/chat/conversations/search`, { params: { q } });
  }

  getMessages(conversationId: number): Observable<StoredChatMessage[]> {
    return this.http.get<StoredChatMessage[]>(`${this.api}/chat/conversations/${conversationId}/messages`);
  }

  deleteConversation(conversationId: number): Observable<void> {
    return this.http.delete<void>(`${this.api}/chat/conversations/${conversationId}`);
  }

  pinConversation(conversationId: number): Observable<ConversationSummary> {
    return this.http.post<ConversationSummary>(`${this.api}/chat/conversations/${conversationId}/pin`, {});
  }

  unpinConversation(conversationId: number): Observable<ConversationSummary> {
    return this.http.delete<ConversationSummary>(`${this.api}/chat/conversations/${conversationId}/pin`);
  }
}
