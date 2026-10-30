import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService, UserSummary } from '../../services/auth.service';

/** Liste de tous les comptes de l'Assistant T24 (page "Utilisateurs" du super administrateur) — remplace l'ancienne
 *  page "Demandes de réinitialisation", devenue obsolète depuis que la réinitialisation de mot de passe est en
 *  libre-service (voir PasswordResetController#resetSelf). */
@Component({
  selector: 'app-users',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './users.component.html',
  styleUrls: ['./users.component.scss']
})
export class UsersComponent implements OnInit {
  users: UserSummary[] = [];
  loading = true;
  error = '';
  search = '';

  constructor(private auth: AuthService) {}

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = '';
    this.auth.getAllUsers().subscribe({
      next: list => { this.users = list; this.loading = false; },
      error: () => { this.error = "Impossible de charger la liste des utilisateurs pour le moment."; this.loading = false; }
    });
  }

  get filteredUsers(): UserSummary[] {
    const term = this.search.trim().toLowerCase();
    if (!term) return this.users;
    return this.users.filter(u => u.username.toLowerCase().includes(term) || u.email.toLowerCase().includes(term));
  }

  displayRole(role: string): string {
    const r = role.toLowerCase();
    if (r === 'superadmin') return 'Super Admin';
    if (r === 'admin') return 'Admin';
    if (r === 'consultant') return 'Consultant';
    return role;
  }

  formatDateTime(iso: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' })
      + ' à ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }
}
