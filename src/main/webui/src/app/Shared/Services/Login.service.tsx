/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
import { Base64 } from 'js-base64';
import { combineLatest, Observable, ObservableInput, of, ReplaySubject } from 'rxjs';
import { fromFetch } from 'rxjs/fetch';
import { catchError, concatMap, debounceTime, distinctUntilChanged, first, map, tap } from 'rxjs/operators';
import { SettingsService } from './Settings.service';
import { ApiV2Response, HttpError } from './Api.service';
import { TargetService } from './Target.service';
import { Credential, JmxCredentials } from './JmxCredentials.service';

export enum SessionState {
  NO_USER_SESSION,
  CREATING_USER_SESSION,
  USER_SESSION,
}

export enum AuthMethod {
  BASIC = 'Basic',
  BEARER = 'Bearer',
  NONE = 'None',
  UNKNOWN = '',
}

export class LoginService {
  private readonly TOKEN_KEY: string = 'token';
  private readonly USER_KEY: string = 'user';
  private readonly AUTH_METHOD_KEY: string = 'auth_method';
  private readonly token = new ReplaySubject<string>(1);
  private readonly authMethod = new ReplaySubject<AuthMethod>(1);
  private readonly logout = new ReplaySubject<void>(1);
  private readonly username = new ReplaySubject<string>(1);
  private readonly sessionState = new ReplaySubject<SessionState>(1);
  readonly authority: string;

  constructor(
    private readonly target: TargetService,
    private readonly jmxCredentials: JmxCredentials,
    private readonly settings: SettingsService
  ) {
    let apiAuthority = process.env.CRYOSTAT_AUTHORITY;
    if (!apiAuthority) {
      apiAuthority = '';
    }
    this.authority = apiAuthority;
    this.token.next(this.getCacheItem(this.TOKEN_KEY));
    this.username.next(this.getCacheItem(this.USER_KEY));
    this.authMethod.next(this.getCacheItem(this.AUTH_METHOD_KEY) as AuthMethod);
    this.sessionState.next(SessionState.NO_USER_SESSION);
    this.queryAuthMethod();
  }

  queryAuthMethod(): void {
    this.checkAuth('', '').subscribe(() => {
      // check auth once at component load to query the server's auth method
    });
  }

  checkAuth(token: string, method: string, rememberMe = true): Observable<boolean> {
    token = Base64.encodeURL(token || this.getTokenFromUrlFragment());
    token = token || this.getCachedEncodedTokenIfAvailable();

    if (this.hasBearerTokenUrlHash()) {
      method = AuthMethod.BEARER;
    }

    if (!method) {
      method = this.getCacheItem(this.AUTH_METHOD_KEY);
    }

    return fromFetch(`${this.authority}/api/v2.1/auth`, {
      credentials: 'include',
      mode: 'cors',
      method: 'POST',
      body: null,
      headers: this.getAuthHeaders(token, method),
    }).pipe(
      concatMap((response) => {
        if (!this.authMethod.isStopped) {
          this.completeAuthMethod(response.headers.get('X-WWW-Authenticate') || '');
        }

        if (response.status === 302) {
          const redirectUrl = response.headers.get('X-Location');

          if (redirectUrl) {
            window.location.replace(redirectUrl);
          }
        }

        return response.json();
      }),
      first(),
      tap((jsonResp: AuthV2Response) => {
        if (jsonResp.meta.status === 'OK') {
          this.decideRememberCredentials(token, jsonResp.data.result.username, rememberMe);
          this.sessionState.next(SessionState.CREATING_USER_SESSION);
        }
      }),
      map((jsonResp: AuthV2Response) => {
        return jsonResp.meta.status === 'OK';
      }),
      catchError((e: Error): ObservableInput<boolean> => {
        window.console.error(JSON.stringify(e));
        this.authMethod.complete();
        return of(false);
      })
    );
  }

  getAuthHeaders(token: string, method: string, jmxCredential?: Credential): Headers {
    const headers = new window.Headers();
    if (!!token && !!method) {
      headers.set('Authorization', `${method} ${token}`);
    } else if (method === AuthMethod.NONE) {
      headers.set('Authorization', AuthMethod.NONE);
    }
    if (jmxCredential) {
      let basic = `${jmxCredential.username}:${jmxCredential.password}`;
      headers.set('X-JMX-Authorization', `Basic ${Base64.encode(basic)}`);
    }
    return headers;
  }

  getHeaders(): Observable<Headers> {
    return combineLatest([
      this.getToken(),
      this.getAuthMethod(),
      this.target.target().pipe(
        map((target) => target.connectUrl),
        concatMap((connect) => this.jmxCredentials.getCredential(connect))
      ),
    ]).pipe(
      map((parts: [string, AuthMethod, Credential | undefined]) => this.getAuthHeaders(parts[0], parts[1], parts[2])),
      first()
    );
  }

  getToken(): Observable<string> {
    return this.token.asObservable();
  }

  getAuthMethod(): Observable<AuthMethod> {
    return this.authMethod.asObservable();
  }

  getUsername(): Observable<string> {
    return this.username.asObservable();
  }

  getSessionState(): Observable<SessionState> {
    return this.sessionState
      .asObservable()
      .pipe(distinctUntilChanged(), debounceTime(this.settings.webSocketDebounceMs()));
  }

  loggedOut(): Observable<void> {
    return this.logout.asObservable();
  }

  setLoggedOut(): Observable<boolean> {
    return combineLatest([this.getToken(), this.getAuthMethod()]).pipe(
      first(),
      concatMap((parts) => {
        const token = parts[0];
        const method = parts[1];

        return fromFetch(`${this.authority}/api/v2.1/logout`, {
          credentials: 'include',
          mode: 'cors',
          method: 'POST',
          body: null,
          headers: this.getAuthHeaders(token, method),
        });
      }),
      concatMap((response) => {
        if (response.status === 302) {
          const redirectUrl = response.headers.get('X-Location');
          if (!redirectUrl) {
            throw new HttpError(response);
          }

          return fromFetch(redirectUrl, {
            credentials: 'include',
            mode: 'cors',
            method: 'POST',
            body: null,
          });
        } else {
          return of(response);
        }
      }),
      map((response) => response.ok),
      tap((responseOk) => {
        if (responseOk) {
          this.resetSessionState();
          this.navigateToLoginPage();
        }
      }),
      catchError((e: Error): ObservableInput<boolean> => {
        window.console.error(JSON.stringify(e));
        return of(false);
      })
    );
  }

  setSessionState(state: SessionState): void {
    this.sessionState.next(state);
  }

  private resetSessionState(): void {
    this.token.next(this.getCacheItem(this.TOKEN_KEY));
    this.username.next(this.getCacheItem(this.USER_KEY));
    this.logout.next();
    this.sessionState.next(SessionState.NO_USER_SESSION);
  }

  private navigateToLoginPage(): void {
    this.authMethod.next(AuthMethod.UNKNOWN);
    this.removeCacheItem(this.AUTH_METHOD_KEY);
    window.location.href = window.location.href.split('#')[0];
  }

  private getTokenFromUrlFragment(): string {
    var matches = location.hash.match(new RegExp('access_token' + '=([^&]*)'));
    return matches ? matches[1] : '';
  }

  private hasBearerTokenUrlHash(): boolean {
    var matches = location.hash.match('token_type=Bearer');
    return !!matches;
  }

  private getCachedEncodedTokenIfAvailable(): string {
    return this.getCacheItem(this.TOKEN_KEY);
  }

  private decideRememberCredentials(token: string, username: string, rememberMe: boolean): void {
    this.token.next(token);
    this.username.next(username);

    if (rememberMe && !!token) {
      this.setCacheItem(this.TOKEN_KEY, token);
      this.setCacheItem(this.USER_KEY, username);
    } else {
      this.removeCacheItem(this.TOKEN_KEY);
      this.removeCacheItem(this.USER_KEY);
    }
  }

  private completeAuthMethod(method: string): void {
    let validMethod = method as AuthMethod;

    if (!Object.values(AuthMethod).includes(validMethod)) {
      validMethod = AuthMethod.UNKNOWN;
    }

    this.authMethod.next(validMethod);
    this.setCacheItem(this.AUTH_METHOD_KEY, validMethod);
    this.authMethod.complete();
  }

  private getCacheItem(key: string): string {
    const item = sessionStorage.getItem(key);
    return !!item ? item : '';
  }

  private setCacheItem(key: string, token: string): void {
    try {
      sessionStorage.setItem(key, token);
    } catch (error) {
      console.error('Caching Failed', (error as any).message);
      sessionStorage.clear();
    }
  }

  private removeCacheItem(key: string): void {
    sessionStorage.removeItem(key);
  }
}

interface AuthV2Response extends ApiV2Response {
  data: {
    result: {
      username: string;
    };
  };
}
