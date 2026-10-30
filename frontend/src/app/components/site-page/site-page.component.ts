import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';

type SiteKey = 'about' | 'features' | 'assistant';

interface Card {
  title: string;
  text: string;
  icon: string;
  tone: string;
}

interface SiteContent {
  title: string;
  highlight: string;
  lead: string;
  cards: Card[];
}

const CONTENT: Record<SiteKey, SiteContent> = {
  about: {
    title: 'À propos de',
    highlight: 'T24 Assistant',
    lead: 'Une solution développée par BIAT IT pour faciliter l\'accès à la documentation du système Temenos T24 de manière rapide, fiable et sécurisée.',
    cards: [
      { icon: 'target', tone: 'cyan', title: 'Notre mission', text: 'Simplifier l\'accès à l\'information T24 pour tous les collaborateurs.' },
      { icon: 'users', tone: 'green', title: 'Notre public', text: 'Équipes de BIAT IT impliquées dans les projets T24.' },
      { icon: 'shield', tone: 'orange', title: 'Notre engagement', text: 'Une information fiable, sécurisée et toujours à jour.' }
    ]
  },
  features: {
    title: 'Des fonctionnalités',
    highlight: 'pensées pour vos besoins',
    lead: 'Explorez, comprenez et trouvez rapidement la documentation T24.',
    cards: [
      { icon: 'search', tone: 'blue', title: 'Recherche rapide', text: 'Trouvez modules, tables et champs en quelques secondes.' },
      { icon: 'doc', tone: 'violet', title: 'Documentation officielle', text: 'Accédez au contenu T24 de référence.' },
      { icon: 'chat', tone: 'green', title: 'Assistant conversationnel', text: 'Posez vos questions en langage naturel.' },
      { icon: 'layers', tone: 'red', title: 'Consultation des modules', text: 'Explorez l\'ensemble des modules T24.' },
      { icon: 'link', tone: 'cyan', title: 'Analyse des processus', text: 'Comprenez les relations entre les données.' },
      { icon: 'bolt', tone: 'orange', title: 'Gain de temps', text: 'Des réponses précises sans recherche manuelle.' }
    ]
  },
  assistant: {
    title: 'Votre allié pour la',
    highlight: 'documentation T24',
    lead: 'Posez vos questions en langage naturel et obtenez des réponses précises basées sur la documentation officielle.',
    cards: [
      { icon: 'chat', tone: 'blue', title: 'Réponses précises', text: 'Des réponses basées sur la documentation officielle T24.' },
      { icon: 'bolt', tone: 'violet', title: 'Compréhension contextuelle', text: 'Analyse vos questions pour fournir des réponses pertinentes.' },
      { icon: 'check', tone: 'green', title: 'Langage naturel', text: 'Posez vos questions simplement, comme dans une conversation.' },
      { icon: 'shield', tone: 'cyan', title: 'Information fiable', text: 'Des réponses issues de sources internes vérifiées.' }
    ]
  }
};

@Component({
  selector: 'app-site-page',
  standalone: true,
  imports: [CommonModule, RouterLink],
  templateUrl: './site-page.component.html',
  styleUrls: ['./site-page.component.scss']
})
export class SitePageComponent {
  readonly page: SiteKey;
  readonly content: SiteContent;

  constructor(route: ActivatedRoute, public auth: AuthService, private router: Router) {
    this.page = (route.snapshot.data['page'] as SiteKey) ?? 'about';
    this.content = CONTENT[this.page];
  }

  get entryLink(): string {
    return this.auth.isLoggedIn() ? '/home' : '/login';
  }

  goToEntry(): void {
    this.router.navigateByUrl(this.entryLink);
  }
}
