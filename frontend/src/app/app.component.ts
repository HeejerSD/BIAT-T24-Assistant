import { Component } from '@angular/core';
import { RouterOutlet, RouterLink, Router, NavigationEnd } from '@angular/router';
import { CommonModule } from '@angular/common';
import { filter } from 'rxjs';
import { NavbarComponent } from './components/navbar/navbar.component';

/** Pages publiques de la vitrine : elles ont leur propre barre de navigation, sans la navbar de l'application. */
const PUBLIC_SITE_PAGES = ['/', '/a-propos', '/fonctionnalites', '/assistant'];

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, CommonModule, NavbarComponent],
  template: `
    <ng-container *ngIf="showChrome">
      <app-navbar></app-navbar>
    </ng-container>
    <main [class.no-chrome]="!showChrome" [class.full-height]="isChatRoute">
      <router-outlet></router-outlet>
    </main>
    <ng-container *ngIf="showChrome && !isChatRoute">
      <a routerLink="/chat" class="chatbot-fab" aria-label="Ouvrir l'assistant T24">
        <svg width="26" height="26" viewBox="0 0 24 24" fill="none" stroke="#fff" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <rect x="4" y="6" width="16" height="12" rx="3"/>
          <circle cx="9" cy="12" r="1.2" fill="#fff" stroke="none"/>
          <circle cx="15" cy="12" r="1.2" fill="#fff" stroke="none"/>
          <path d="M12 6V3"/>
          <circle cx="12" cy="2.5" r="0.8" fill="#fff" stroke="none"/>
        </svg>
      </a>
    </ng-container>
  `,
  styles: [`
    main { min-height: calc(100vh - 64px); }
    main.no-chrome { min-height: 100vh; }
    main.full-height { margin-top: 64px; height: calc(100vh - 64px); min-height: 0; overflow: hidden; }
    .chatbot-fab {
      position: fixed;
      right: 24px;
      bottom: 24px;
      width: 56px;
      height: 56px;
      border-radius: 50%;
      background: #1A4F8A;
      display: flex;
      align-items: center;
      justify-content: center;
      box-shadow: 0 6px 20px rgba(26,79,138,0.35);
      z-index: 200;
      transition: transform 0.2s ease, box-shadow 0.2s ease;
    }
    .chatbot-fab:hover {
      transform: translateY(-2px) scale(1.05);
      box-shadow: 0 10px 26px rgba(26,79,138,0.4);
    }
  `]
})
export class AppComponent {
  showChrome = true;
  isChatRoute = false;

  constructor(private router: Router) {
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => {
        const url = e.urlAfterRedirects;
        this.showChrome = !url.startsWith('/login') && !PUBLIC_SITE_PAGES.includes(url);
        this.isChatRoute = e.urlAfterRedirects.startsWith('/chat');
      });
  }
}
