import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.scss']
})
export class LoginComponent {
  /** Intégré dans la page d'accueil : pas de fond plein écran, logo ni pied de page propres. */
  @Input() embedded = false;

  mode: 'login' | 'register' = 'login';

  username = '';
  password = '';
  showPassword = false;
  error = '';
  loading = false;
  showForgotPassword = false;
  showResetForm = false;
  resetLoading = false;
  resetMessage = '';
  resetError = '';
  resetNewPassword = '';
  resetConfirmPassword = '';

  regUsername = '';
  regEmail = '';
  regPassword = '';
  regConfirmPassword = '';
  regLoading = false;
  regError = '';
  regSuccess = '';

  private readonly EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

  constructor(private auth: AuthService, private router: Router) {
    if (this.auth.isLoggedIn()) {
      this.router.navigate(['/home']);
    }
  }

  switchToRegister(): void {
    this.mode = 'register';
    this.error = '';
    this.showForgotPassword = false;
  }

  switchToLogin(): void {
    this.mode = 'login';
    this.regError = '';
    this.regSuccess = '';
  }

  onSubmit(): void {
    // Formulaire de réinitialisation affiché (champ mot de passe caché) : la touche Entrée doit valider CE
    // formulaire-là, pas tenter une connexion avec un mot de passe périmé resté dans le champ masqué.
    if (this.showResetForm) {
      this.confirmPasswordReset();
      return;
    }
    if (!this.username.trim() || !this.password.trim()) {
      this.error = 'Veuillez remplir tous les champs.';
      return;
    }
    this.loading = true;
    this.error = '';
    this.showForgotPassword = false;
    this.resetMessage = '';

    this.auth.login(this.username, this.password).subscribe({
      next: () => this.router.navigate(['/home']),
      error: () => {
        this.error = 'Identifiants incorrects. Veuillez réessayer.';
        this.showForgotPassword = true;
        this.loading = false;
      }
    });
  }

  togglePassword(): void {
    this.showPassword = !this.showPassword;
  }

  /** Révèle le mini-formulaire de réinitialisation directe (nouveau mot de passe, 2 fois) : plus de demande à
   *  transmettre au super administrateur, l'utilisateur redéfinit lui-même son mot de passe immédiatement. */
  openResetForm(): void {
    if (!this.username.trim()) {
      this.resetError = "Saisissez d'abord votre nom d'utilisateur ci-dessus.";
      return;
    }
    this.resetError = '';
    this.resetMessage = '';
    this.resetNewPassword = '';
    this.resetConfirmPassword = '';
    this.error = '';
    this.showResetForm = true;
  }

  cancelReset(): void {
    this.showResetForm = false;
    this.resetError = '';
  }

  confirmPasswordReset(): void {
    this.resetError = '';
    if (!this.resetNewPassword || !this.resetConfirmPassword) {
      this.resetError = 'Veuillez remplir les deux champs.';
      return;
    }
    if (this.resetNewPassword.length < 6) {
      this.resetError = 'Le mot de passe doit contenir au moins 6 caractères.';
      return;
    }
    if (this.resetNewPassword !== this.resetConfirmPassword) {
      this.resetError = 'La confirmation ne correspond pas au mot de passe.';
      return;
    }
    this.resetLoading = true;
    this.auth.resetPasswordSelf(this.username.trim(), this.resetNewPassword, this.resetConfirmPassword).subscribe({
      next: res => {
        this.resetLoading = false;
        this.showResetForm = false;
        this.showForgotPassword = false;
        this.resetMessage = res.message;
        // Le champ mot de passe réapparaît avec le formulaire de connexion (showResetForm redevient false) :
        // on efface l'ancien message "Identifiants incorrects" (resté affiché depuis la tentative précédente,
        // simplement masqué pendant que le formulaire de réinitialisation était ouvert), sinon il réapparaît à
        // tort juste après une réinitialisation pourtant réussie — déjà prérempli avec le nouveau mot de passe.
        this.error = '';
        this.password = this.resetNewPassword;
        this.resetNewPassword = '';
        this.resetConfirmPassword = '';
      },
      error: err => {
        this.resetLoading = false;
        this.resetError = err?.error?.message || 'Impossible de réinitialiser le mot de passe pour le moment.';
      }
    });
  }

  onRegisterSubmit(): void {
    this.regError = '';
    this.regSuccess = '';

    const username = this.regUsername.trim();
    const email = this.regEmail.trim();

    if (!username || !email || !this.regPassword || !this.regConfirmPassword) {
      this.regError = 'Veuillez remplir tous les champs.';
      return;
    }
    if (username.length < 3) {
      this.regError = "Le nom d'utilisateur doit contenir au moins 3 caractères.";
      return;
    }
    if (!this.EMAIL_REGEX.test(email)) {
      this.regError = 'Adresse email invalide (ex: nom@biat.tn).';
      return;
    }
    if (this.regPassword.length < 6) {
      this.regError = 'Le mot de passe doit contenir au moins 6 caractères.';
      return;
    }
    if (this.regPassword !== this.regConfirmPassword) {
      this.regError = 'La confirmation ne correspond pas au mot de passe.';
      return;
    }

    this.regLoading = true;
    this.auth.registerPublic(username, email, this.regPassword).subscribe({
      next: () => {
        this.regSuccess = 'Compte créé ! Vous pouvez maintenant vous connecter.';
        this.regLoading = false;
        this.username = username;
        this.regUsername = '';
        this.regEmail = '';
        this.regPassword = '';
        this.regConfirmPassword = '';
        setTimeout(() => this.switchToLogin(), 1500);
      },
      error: err => {
        this.regError = err?.status === 409
          ? (err?.error?.message || "Ce nom d'utilisateur existe déjà.")
          : (err?.error?.message || 'Impossible de créer ce compte.');
        this.regLoading = false;
      }
    });
  }
}
