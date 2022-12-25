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

import * as _ from 'lodash';
import { ApiService } from './Api.service';
import { Target } from './Target.service';
import { Notifications } from '@app/Notifications/Notifications';
import { NotificationCategory, NotificationChannel } from './NotificationChannel.service';
import { Observable, BehaviorSubject, of, EMPTY } from 'rxjs';
import { catchError, concatMap, first, map, tap } from 'rxjs/operators';
import { LoginService, SessionState } from './Login.service';

export interface TargetDiscoveryEvent {
  kind: 'LOST' | 'FOUND';
  serviceRef: Target;
}

export class TargetsService {
  private readonly _targets$: BehaviorSubject<Target[]> = new BehaviorSubject<Target[]>([] as Target[]);

  constructor(
    private readonly api: ApiService,
    private readonly notifications: Notifications,
    login: LoginService,
    notificationChannel: NotificationChannel
  ) {
    login
      .getSessionState()
      .pipe(concatMap((sessionState) => (sessionState === SessionState.USER_SESSION ? this.queryForTargets() : EMPTY)))
      .subscribe(() => {
        // just trigger a startup query
      });
    notificationChannel.messages(NotificationCategory.TargetJvmDiscovery).subscribe((v) => {
      const evt: TargetDiscoveryEvent = v.message.event;
      switch (evt.kind) {
        case 'FOUND':
          this._targets$.next(_.unionBy(this._targets$.getValue(), [evt.serviceRef], (t) => t.connectUrl));
          break;
        case 'LOST':
          this._targets$.next(_.filter(this._targets$.getValue(), (t) => t.connectUrl !== evt.serviceRef.connectUrl));
          break;
        default:
          break;
      }
    });
  }

  queryForTargets(): Observable<void> {
    return this.api.doGet<Target[]>(`targets`).pipe(
      first(),
      tap((targets) => this._targets$.next(targets)),
      map(() => undefined),
      catchError((err) => {
        this.notifications.danger('Target List Update Failed', JSON.stringify(err));
        return of(undefined);
      })
    );
  }

  targets(): Observable<Target[]> {
    return this._targets$.asObservable();
  }
}
