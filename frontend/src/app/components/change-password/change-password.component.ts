import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-change-password',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './change-password.component.html',
  styleUrls: ['./change-password.component.scss']
})
export class ChangePasswordComponent {
  currentPassword = '';
  newPassword = '';
  confirmPassword = '';

  loading = false;
  error = '';
  success = '';

  constructor(private auth: AuthService) {}

  onSubmit(): void {
    this.error = '';
    this.success = '';

    if (!this.currentPassword || !this.newPassword || !this.confirmPassword) {
      this.error = 'Veuillez remplir tous les champs.';
      return;
    }
    if (this.newPassword.length < 6) {
      this.error = 'Le nouveau mot de passe doit contenir au moins 6 caractères.';
      return;
    }
    if (this.newPassword !== this.confirmPassword) {
      this.error = 'La confirmation ne correspond pas au nouveau mot de passe.';
      return;
    }

    this.loading = true;
    this.auth.changePassword(this.currentPassword, this.newPassword).subscribe({
      next: () => {
        this.success = 'Mot de passe modifié avec succès.';
        this.currentPassword = '';
        this.newPassword = '';
        this.confirmPassword = '';
        this.loading = false;
      },
      error: err => {
        this.error = err?.status === 401
          ? 'Mot de passe actuel incorrect.'
          : (err?.error?.message || 'Impossible de modifier le mot de passe.');
        this.loading = false;
      }
    });
  }
}
