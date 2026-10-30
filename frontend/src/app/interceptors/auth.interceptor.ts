import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const token = auth.getToken();
  const authedReq = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authedReq).pipe(
    catchError(err => {
      // 401 = token absent/invalide/expiré (ex: backend redémarré, sa clé de signature a changé) -> reconnexion forcée.
      // (403 = rôle insuffisant pour un utilisateur valide, ce n'est pas un problème de session : on ne déconnecte pas.)
      if (err.status === 401 && auth.isLoggedIn() && !req.url.endsWith('/auth/login')) {
        auth.logout();
      }
      return throwError(() => err);
    })
  );
};
