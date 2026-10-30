import { Component, OnDestroy, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AuthService, DashboardStats } from '../../services/auth.service';

/** Intervalle de rafraîchissement automatique (ms) : le dashboard reflète les changements faits en base (ex.
 *  utilisateurs supprimés directement en BDD) ou par l'utilisation du site sans qu'il faille recharger la page. */
const AUTO_REFRESH_MS = 20000;

/** Dashboard super administrateur : vue d'ensemble réelle (base de données) de l'utilisation de l'Assistant T24
 *  sur une période choisie — remplace l'ancienne icône "Demandes de réinitialisation" de la navbar, le flux de
 *  réinitialisation de mot de passe étant désormais en libre-service. Se rafraîchit automatiquement (toutes les
 *  {@link AUTO_REFRESH_MS} et quand l'onglet redevient visible), jamais figé sur une donnée périmée. */
@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.scss']
})
export class DashboardComponent implements OnInit, OnDestroy {
  stats: DashboardStats | null = null;
  loading = true;
  error = '';
  refreshing = false;

  from = '';
  to = '';

  private refreshTimer: ReturnType<typeof setInterval> | null = null;
  private onVisibilityChange = () => {
    if (document.visibilityState === 'visible') this.load(true);
  };

  constructor(private auth: AuthService) {}

  ngOnInit(): void {
    const today = new Date();
    const past = new Date();
    past.setDate(past.getDate() - 29);
    this.to = this.toInputDate(today);
    this.from = this.toInputDate(past);
    this.load();

    this.refreshTimer = setInterval(() => this.load(true), AUTO_REFRESH_MS);
    document.addEventListener('visibilitychange', this.onVisibilityChange);
  }

  ngOnDestroy(): void {
    if (this.refreshTimer) clearInterval(this.refreshTimer);
    document.removeEventListener('visibilitychange', this.onVisibilityChange);
  }

  private toInputDate(d: Date): string {
    return d.toISOString().slice(0, 10);
  }

  /** @param background vrai pour un rafraîchissement automatique silencieux (pas de spinner plein écran, les
   *  données affichées restent visibles jusqu'à ce que les nouvelles arrivent). */
  load(background = false): void {
    if (background) this.refreshing = true;
    else this.loading = true;
    this.error = '';
    this.auth.getDashboardStats(this.from, this.to).subscribe({
      next: s => { this.stats = s; this.loading = false; this.refreshing = false; },
      error: () => {
        this.loading = false;
        this.refreshing = false;
        if (!background) this.error = "Impossible de charger les statistiques pour le moment.";
      }
    });
  }

  onRangeChange(): void {
    if (this.from && this.to) this.load();
  }

  /** Points normalisés (0-100 en x et y) du graphique d'activité, pour un polyline SVG viewBox="0 0 100 100". */
  get activityPoints(): string {
    const data = this.stats?.dailyActivity ?? [];
    if (data.length === 0) return '';
    const max = Math.max(1, ...data.map(d => d.count));
    const stepX = data.length > 1 ? 100 / (data.length - 1) : 0;
    return data.map((d, i) => {
      const x = data.length > 1 ? i * stepX : 50;
      const y = 100 - (d.count / max) * 92 - 4;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    }).join(' ');
  }

  /** Mêmes points que {@link activityPoints}, refermés sur la ligne de base (bas du graphique) pour dessiner
   *  l'aire remplie sous la courbe. */
  get activityAreaPoints(): string {
    const line = this.activityPoints;
    if (!line) return '';
    return `0,100 ${line} 100,100`;
  }

  get activityMax(): number {
    const data = this.stats?.dailyActivity ?? [];
    return Math.max(1, ...data.map(d => d.count));
  }

  /** Un point sur 1, 2, 3... selon la densité, pour ne jamais surcharger l'axe (voir anti-patterns : pas une
   *  étiquette par point). */
  activityLabelEvery(): number {
    const n = this.stats?.dailyActivity.length ?? 0;
    if (n <= 10) return 1;
    if (n <= 20) return 2;
    return Math.ceil(n / 10);
  }

  formatShortDate(iso: string): string {
    const d = new Date(iso + 'T00:00:00');
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' });
  }

  formatDateTime(iso: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' })
      + ' à ' + d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
  }

  /** Décalage cumulé (en %) du segment "actifs" du donut, pour positionner son arc via stroke-dasharray. */
  get donutActiveDasharray(): string {
    const pct = this.stats?.activeUsersPct ?? 0;
    const circumference = 2 * Math.PI * 40;
    const active = (pct / 100) * circumference;
    return `${active.toFixed(2)} ${circumference.toFixed(2)}`;
  }
}
