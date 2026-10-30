import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ChatService, ColumnDescription } from '../../services/chat.service';

@Component({
  selector: 'app-table-detail',
  standalone: true,
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './table-detail.component.html',
  styleUrls: ['./table-detail.component.scss']
})
export class TableDetailComponent implements OnInit {
  moduleName = '';
  tableName = '';
  columns: ColumnDescription[] = [];
  loading = true;
  error = '';
  columnSearch = '';

  get filteredColumns(): ColumnDescription[] {
    const term = this.columnSearch.trim().toUpperCase();
    if (!term) return this.columns;
    return this.columns.filter(c => c.columnName.toUpperCase().includes(term));
  }

  constructor(
    private route: ActivatedRoute,
    private router: Router,
    private chatService: ChatService
  ) {}

  ngOnInit() {
    this.route.paramMap.subscribe(params => {
      this.moduleName = params.get('name') || '';
      this.tableName = params.get('table') || '';
      this.loadColumns();
    });
  }

  loadColumns() {
    if (!this.tableName) return;
    this.loading = true;
    this.error = '';
    this.chatService.getTableColumnDescriptions(this.tableName).subscribe({
      next: columns => {
        this.columns = columns;
        this.loading = false;
      },
      error: () => {
        this.error = 'Impossible de charger les colonnes de cette table.';
        this.loading = false;
      }
    });
  }

  askInChat() {
    this.router.navigate(['/chat'], {
      queryParams: { module: this.moduleName, table: this.tableName }
    });
  }
}
