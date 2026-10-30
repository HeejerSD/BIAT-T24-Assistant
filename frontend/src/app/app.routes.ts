import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';
import { adminGuard } from './guards/admin.guard';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadComponent: () => import('./components/welcome/welcome.component').then(m => m.WelcomeComponent)
  },
  {
    path: 'a-propos',
    data: { page: 'about' },
    loadComponent: () => import('./components/site-page/site-page.component').then(m => m.SitePageComponent)
  },
  {
    path: 'fonctionnalites',
    data: { page: 'features' },
    loadComponent: () => import('./components/site-page/site-page.component').then(m => m.SitePageComponent)
  },
  {
    path: 'assistant',
    data: { page: 'assistant' },
    loadComponent: () => import('./components/site-page/site-page.component').then(m => m.SitePageComponent)
  },
  {
    path: 'login',
    loadComponent: () => import('./components/login/login.component').then(m => m.LoginComponent)
  },
  {
    path: 'home',
    canActivate: [authGuard],
    loadComponent: () => import('./components/home/home.component').then(m => m.HomeComponent)
  },
  {
    path: 'modules/:name',
    canActivate: [authGuard],
    loadComponent: () => import('./components/module-tables/module-tables.component').then(m => m.ModuleTablesComponent)
  },
  {
    path: 'modules/:name/tables/:table',
    canActivate: [authGuard],
    loadComponent: () => import('./components/table-detail/table-detail.component').then(m => m.TableDetailComponent)
  },
  {
    path: 'admin/users',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./components/users/users.component').then(m => m.UsersComponent)
  },
  {
    path: 'admin/dashboard',
    canActivate: [authGuard, adminGuard],
    loadComponent: () => import('./components/dashboard/dashboard.component').then(m => m.DashboardComponent)
  },
  {
    path: 'account/password',
    canActivate: [authGuard],
    loadComponent: () => import('./components/change-password/change-password.component').then(m => m.ChangePasswordComponent)
  },
  {
    path: 'about',
    loadComponent: () => import('./components/about/about.component').then(m => m.AboutComponent)
  },
  {
    path: 'chat',
    canActivate: [authGuard],
    loadComponent: () => import('./components/chat/chat.component').then(m => m.ChatComponent)
  },
  { path: '**', redirectTo: '' }
];
