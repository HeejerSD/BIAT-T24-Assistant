import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ChatService, T24Table } from '../../services/chat.service';

@Component({
  selector: 'app-module-tables',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './module-tables.component.html',
  styleUrls: ['./module-tables.component.scss']
})
export class ModuleTablesComponent implements OnInit {
  moduleName = '';
  tables: T24Table[] = [];
  loading = true;
  error = '';
  tableSearch = '';

  get filteredTables(): T24Table[] {
    const term = this.tableSearch.trim().toUpperCase();
    if (!term) return this.tables;
    return this.tables.filter(t => t.name.toUpperCase().startsWith(term));
  }

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private chatService: ChatService
  ) {}

  ngOnInit() {
    this.route.paramMap.subscribe(params => {
      this.moduleName = params.get('name') || '';
      this.loadTables();
    });
  }

  loadTables() {
    if (!this.moduleName) return;
    this.loading = true;
    this.error = '';
    this.chatService.getTablesByModule(this.moduleName).subscribe({
      next: tables => {
        this.tables = tables;
        this.loading = false;
      },
      error: () => {
        this.error = 'Impossible de charger les tables de ce module.';
        this.loading = false;
      }
    });
  }

  openTable(table: T24Table) {
    this.router.navigate(['/modules', this.moduleName, 'tables', table.name]);
  }
}
