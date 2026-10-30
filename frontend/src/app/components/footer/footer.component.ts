import { Component } from '@angular/core';

@Component({
  selector: 'app-footer',
  standalone: true,
  template: `
    <footer class="footer">
      <div class="container footer-inner">
        <div class="footer-left">
          <div class="footer-logo">
            <svg width="18" height="18" viewBox="0 0 60 60" fill="none">
              <path d="M10 50L25 10L35 35L45 20L55 50Z" fill="#1A4F8A"/>
              <path d="M10 50L25 10L35 35Z" fill="#009FC2" opacity=".7"/>
            </svg>
            <span>BIAT Innovation & Technology</span>
          </div>
          <p class="footer-desc">Assistant intelligent pour la consultation des tables T24 — Usage interne.</p>
        </div>
        <div class="footer-right">
          <span class="footer-tag">Spring Boot 3.2</span>
          <span class="footer-tag">Angular 17</span>
          <span class="footer-tag">Claude IA</span>
        </div>
      </div>
    </footer>
  `,
  styles: [`
    .footer { background: var(--text-primary); padding: 32px 0; }
    .footer-inner { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 20px; }
    .footer-logo { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; color: #fff; margin-bottom: 8px; }
    .footer-desc { font-size: 13px; color: rgba(255,255,255,0.5); }
    .footer-right { display: flex; gap: 8px; flex-wrap: wrap; }
    .footer-tag { background: rgba(255,255,255,0.1); color: rgba(255,255,255,0.6); font-size: 11px; padding: 4px 10px; border-radius: 4px; }
  `]
})
export class FooterComponent {}
