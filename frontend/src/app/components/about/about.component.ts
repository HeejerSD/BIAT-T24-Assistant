import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-about',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './about.component.html',
  styleUrls: ['./about.component.scss']
})
export class AboutComponent {
  features = [
    {
      icon: '🔍',
      title: 'Consultation instantanée',
      desc: 'Posez une question en langage naturel sur n\'importe quelle colonne T24 et obtenez une réponse en quelques secondes.'
    },
    {
      icon: '📋',
      title: 'Obligatoire ou optionnel',
      desc: 'Sachez immédiatement si un champ est MAN (obligatoire), OPT (optionnel) ou NOINP (calculé automatiquement).'
    },
    {
      icon: '📂',
      title: 'Tous les modules T24',
      desc: 'LD (Crédits), AC (Comptes), FT (Virements), SC (Titres) et tous les autres modules de votre instance T24.'
    },
    {
      icon: '🤖',
      title: 'IA enrichie par le helper',
      desc: 'Le chatbot consulte vos fichiers XML et votre helper.txt pour des réponses précises et contextualisées BIAT.'
    },
    {
      icon: '⚡',
      title: 'Indexation en cache',
      desc: 'Les fichiers XML sont indexés en mémoire au démarrage. Réponses rapides, sans latence de lecture disque.'
    },
    {
      icon: '🔒',
      title: 'Usage interne BIAT IT',
      desc: 'Outil réservé aux employés BIAT IT. Accès sécurisé, données bancaires confidentielles.'
    }
  ];
}
