import { Component, OnInit } from '@angular/core';
import { RouterLink, RouterLinkActive } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ChatService, ModuleSummary } from '../../services/chat.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, CommonModule, FormsModule],
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss']
})
export class HomeComponent implements OnInit {
  tablesCount = 0;
  modulesCount = 0;
  questionsAnswered = 0;
  backendOnline = false;
  reindexing = false;
  isSuperAdmin = false;

  modules: string[] = [];
  moduleSearch = '';

  constructor(private chatService: ChatService, private authService: AuthService) {}

  get filteredModules(): string[] {
    const term = this.moduleSearch.trim().toUpperCase();
    if (!term) return this.modules;
    return this.modules.filter(m => m.toUpperCase().startsWith(term));
  }

  ngOnInit() {
    this.chatService.getHealth().subscribe({
      next: (h: any) => {
        this.backendOnline = true;
        this.tablesCount = h.tablesIndexed || 0;
        this.questionsAnswered = h.questionsAnswered || 0;
      },
      error: () => { this.backendOnline = false; }
    });
    this.loadModules();

    this.isSuperAdmin = this.authService.isSuperAdmin();
  }

  loadModules() {
    this.chatService.getModules().subscribe({
      next: (m: ModuleSummary[]) => {
        this.modules = m.map(x => x.code);
        this.modulesCount = m.length;
      },
      error: () => {}
    });
  }

  reindexData() {
    this.reindexing = true;
    this.chatService.reindex().subscribe({
      next: (res: any) => {
        this.tablesCount = res.tablesIndexed || 0;
        this.modules = res.modules || [];
        this.modulesCount = this.modules.length;
        this.backendOnline = true;
        this.reindexing = false;
      },
      error: () => { this.reindexing = false; }
    });
  }

  exploreDocs() {
    window.scrollTo({ top: 0, behavior: 'smooth' });
    document.getElementById('module-search-input')?.focus({ preventScroll: true });
  }
}
