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
import * as React from 'react';
import { BehaviorSubject, Observable } from 'rxjs';
import { concatMap, filter, first, map } from 'rxjs/operators';
import { AlertVariant } from '@patternfly/react-core';
import { nanoid } from 'nanoid';
import { NotificationCategory } from '@app/Shared/Services/NotificationChannel.service';

export interface Notification {
  hidden?: boolean;
  read?: boolean;
  key?: string;
  title: string;
  message?: string | Error;
  category?: string;
  variant: AlertVariant;
  timestamp?: number;
}

export class Notifications {
  private readonly _notifications$: BehaviorSubject<Notification[]> = new BehaviorSubject<Notification[]>([]);
  private readonly _drawerState$: BehaviorSubject<boolean> = new BehaviorSubject<boolean>(false);

  constructor() {
    this._drawerState$
      .pipe(
        filter((v) => v),
        concatMap(() => this._notifications$.pipe(first()))
      )
      .subscribe((prev) =>
        this._notifications$.next(
          prev.map((n) => ({
            ...n,
            hidden: true,
          }))
        )
      );
  }

  drawerState(): Observable<boolean> {
    return this._drawerState$.asObservable();
  }

  setDrawerState(state: boolean): void {
    this._drawerState$.next(state);
  }

  notify(notification: Notification): void {
    if (!notification.key) {
      notification.key = nanoid();
    }
    notification.read = false;
    if (notification.hidden === undefined) {
      notification.hidden = this._drawerState$.getValue();
    }
    notification.timestamp = +Date.now();
    if (notification.message instanceof Error) {
      notification.message = JSON.stringify(notification.message, Object.getOwnPropertyNames(notification.message));
    } else if (typeof notification.message !== 'string') {
      notification.message = JSON.stringify(notification.message);
    }
    this._notifications$.pipe(first()).subscribe((prev) => {
      prev.unshift(notification);
      this._notifications$.next(prev);
    });
  }

  success(title: string, message?: string | Error, category?: string, hidden?: boolean): void {
    this.notify({ title, message, category, variant: AlertVariant.success, hidden });
  }

  info(title: string, message?: string | Error, category?: string, hidden?: boolean): void {
    this.notify({ title, message, category, variant: AlertVariant.info, hidden });
  }

  warning(title: string, message?: string | Error, category?: string): void {
    this.notify({ title, message, category, variant: AlertVariant.warning });
  }

  danger(title: string, message?: string | Error, category?: string): void {
    this.notify({ title, message, category, variant: AlertVariant.danger });
  }

  notifications(): Observable<Notification[]> {
    return this._notifications$.asObservable();
  }

  unreadNotifications(): Observable<Notification[]> {
    return this.notifications().pipe(map((a) => a.filter((n) => !n.read)));
  }

  actionsNotifications(): Observable<Notification[]> {
    return this.notifications().pipe(map((a) => a.filter((n) => this.isActionNotification(n))));
  }

  cryostatStatusNotifications(): Observable<Notification[]> {
    return this.notifications().pipe(
      map((a) =>
        a.filter(
          (n) => (this.isWsClientActivity(n) || this.isJvmDiscovery(n)) && !Notifications.isProblemNotification(n)
        )
      )
    );
  }

  problemsNotifications(): Observable<Notification[]> {
    return this.notifications().pipe(map((a) => a.filter(Notifications.isProblemNotification)));
  }

  setHidden(key?: string, hidden: boolean = true): void {
    if (!key) {
      return;
    }
    this._notifications$.pipe(first()).subscribe((prev) => {
      for (let n of prev) {
        if (n.key === key) {
          n.hidden = hidden;
        }
      }
      this._notifications$.next(prev);
    });
  }

  setRead(key?: string, read: boolean = true): void {
    if (!key) {
      return;
    }
    this._notifications$.pipe(first()).subscribe((prev) => {
      for (let n of prev) {
        if (n.key === key) {
          n.read = read;
        }
      }
      this._notifications$.next(prev);
    });
  }

  markAllRead(): void {
    this._notifications$.pipe(first()).subscribe((prev) => {
      for (let n of prev) {
        n.read = true;
      }
      this._notifications$.next(prev);
    });
  }

  clearAll(): void {
    this._notifications$.next([]);
  }

  private isActionNotification(n: Notification): boolean {
    return !this.isWsClientActivity(n) && !this.isJvmDiscovery(n) && !Notifications.isProblemNotification(n);
  }

  private isWsClientActivity(n: Notification): boolean {
    return n.category === NotificationCategory.WsClientActivity;
  }

  private isJvmDiscovery(n: Notification): boolean {
    return n.category === NotificationCategory.TargetJvmDiscovery;
  }

  static isProblemNotification(n: Notification): boolean {
    return n.variant === AlertVariant.warning || n.variant === AlertVariant.danger;
  }
}

const NotificationsInstance = new Notifications();

const NotificationsContext = React.createContext(NotificationsInstance);

export { NotificationsContext, NotificationsInstance };
