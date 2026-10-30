import { Component, HostListener } from '@angular/core';
import { RouterLink, RouterLinkActive, Router, NavigationEnd } from '@angular/router';
import { CommonModule, Location } from '@angular/common';
import { filter } from 'rxjs';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-navbar',
  standalone: true,
  imports: [RouterLink, RouterLinkActive, CommonModule],
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.scss']
})
export class NavbarComponent {
  scrolled = false;
  menuOpen = false;
  showBack = false;

  constructor(
    private auth: AuthService,
    private location: Location,
    private router: Router
  ) {
    this.showBack = this.computeShowBack(this.router.url);
    this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe(e => this.showBack = this.computeShowBack(e.urlAfterRedirects));
  }

  private computeShowBack(url: string): boolean {
    return url !== '/' && url !== '/home' && !url.startsWith('/login');
  }

  goBack(): void {
    this.location.back();
  }

  get username(): string | null {
    return this.auth.getUsername();
  }

  get isSuperAdmin(): boolean {
    return this.auth.isSuperAdmin();
  }

  @HostListener('window:scroll')
  onScroll() {
    this.scrolled = window.scrollY > 20;
  }

  toggleMenu() {
    this.menuOpen = !this.menuOpen;
  }

  logout() {
    this.menuOpen = false;
    this.auth.logout();
  }
}
