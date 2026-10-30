import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router, RouterLink } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { environment } from '../../../environments/environment';
import { LoginComponent } from '../login/login.component';

interface PublicStats {
  modules: number;
  tables: number;
  users: number;
}

@Component({
  selector: 'app-welcome',
  standalone: true,
  imports: [CommonModule, RouterLink, LoginComponent],
  templateUrl: './welcome.component.html',
  styleUrls: ['./welcome.component.scss']
})
export class WelcomeComponent implements OnInit {
  stats: PublicStats | null = null;

  constructor(public auth: AuthService, private router: Router, private http: HttpClient) {}

  ngOnInit(): void {
    this.http.get<PublicStats>(`${environment.apiUrl}/auth/public-stats`).subscribe({
      next: s => this.stats = s,
      error: () => this.stats = null
    });
  }

  get entryLink(): string {
    return this.auth.isLoggedIn() ? '/home' : '/login';
  }

  goToEntry(): void {
    this.router.navigateByUrl(this.entryLink);
  }

  format(n: number | undefined): string {
    return n === undefined ? '—' : n.toLocaleString('fr-FR');
  }
}
