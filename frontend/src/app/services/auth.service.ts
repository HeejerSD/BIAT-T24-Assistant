import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../environments/environment';

export interface LoginResponse {
  token: string;
  username: string;
  role: string;
}

export interface RegisterResponse {
  username: string;
  role: string;
}

export interface UserSummary {
  username: string;
  email: string;
  role: string;
  active: boolean;
  createdAt: string;
  lastActivityAt: string | null;
}

export interface DashboardRecentUser {
  username: string;
  role: string;
  status: string;
  lastActivityAt: string | null;
}

export interface DashboardDailyCount {
  date: string;
  count: number;
}

export interface DashboardLabeledCount {
  label: string;
  count: number;
  pct: number;
}

export interface DashboardRecentQuestion {
  question: string;
  module: string | null;
  table: string | null;
  username: string | null;
  date: string;
}

export interface DashboardStats {
  from: string;
  to: string;
  totalUsers: number;
  totalUsersDelta: number;
  totalUsersDeltaPct: number;
  activeUsers: number;
  inactiveUsers: number;
  activeUsersPct: number;
  totalQuestions: number;
  totalQuestionsDelta: number;
  totalQuestionsDeltaPct: number;
  recentUsers: DashboardRecentUser[];
  dailyActivity: DashboardDailyCount[];
  topModules: DashboardLabeledCount[];
  topTables: DashboardLabeledCount[];
  topFields: DashboardLabeledCount[];
  recentQuestions: DashboardRecentQuestion[];
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly TOKEN_KEY = 'biat_token';
  private readonly USER_KEY = 'biat_user';
  private readonly ROLE_KEY = 'biat_role';
  private readonly api = environment.apiUrl;

  constructor(private http: HttpClient, private router: Router) {}

  login(username: string, password: string): Observable<LoginResponse> {
    return this.http.post<LoginResponse>(`${this.api}/auth/login`, { username, password }).pipe(
      tap(res => {
        sessionStorage.setItem(this.TOKEN_KEY, res.token);
        sessionStorage.setItem(this.USER_KEY, res.username);
        sessionStorage.setItem(this.ROLE_KEY, res.role);
      })
    );
  }

  logout(): void {
    sessionStorage.removeItem(this.TOKEN_KEY);
    sessionStorage.removeItem(this.USER_KEY);
    sessionStorage.removeItem(this.ROLE_KEY);
    this.router.navigate(['/login']);
  }

  isLoggedIn(): boolean {
    return !!sessionStorage.getItem(this.TOKEN_KEY);
  }

  getToken(): string | null {
    return sessionStorage.getItem(this.TOKEN_KEY);
  }

  getUsername(): string | null {
    return sessionStorage.getItem(this.USER_KEY);
  }

  getRole(): string | null {
    return sessionStorage.getItem(this.ROLE_KEY);
  }

  isSuperAdmin(): boolean {
    return this.getRole() === 'superAdmin';
  }

  /** Inscription publique depuis la page de login : toujours créée avec le rôle "consultant" (imposé côté serveur). */
  registerPublic(username: string, email: string, password: string): Observable<RegisterResponse> {
    return this.http.post<RegisterResponse>(`${this.api}/auth/register-public`, { username, email, password });
  }

  /** Auto-service : l'utilisateur ayant oublié son mot de passe le redéfinit lui-même (saisi deux fois),
   *  sans attendre qu'un super administrateur traite une demande. */
  resetPasswordSelf(username: string, newPassword: string, confirmPassword: string): Observable<{ message: string }> {
    return this.http.post<{ message: string }>(`${this.api}/auth/password-reset/self`,
      { username, newPassword, confirmPassword });
  }

  /** Liste de tous les comptes (page "Utilisateurs" du super administrateur). */
  getAllUsers(): Observable<UserSummary[]> {
    return this.http.get<UserSummary[]>(`${this.api}/auth/users`);
  }

  /** Dashboard super administrateur : données réelles (base) sur la période from..to (YYYY-MM-DD), défaut les
   *  30 derniers jours si omis. */
  getDashboardStats(from?: string, to?: string): Observable<DashboardStats> {
    let params = '';
    if (from || to) {
      const parts: string[] = [];
      if (from) parts.push(`from=${from}`);
      if (to) parts.push(`to=${to}`);
      params = '?' + parts.join('&');
    }
    return this.http.get<DashboardStats>(`${this.api}/admin/dashboard${params}`);
  }

  /** Auto-service : l'utilisateur connecté change son propre mot de passe (nécessite l'ancien). */
  changePassword(currentPassword: string, newPassword: string): Observable<void> {
    return this.http.put<void>(`${this.api}/auth/change-password`, { currentPassword, newPassword });
  }
}
