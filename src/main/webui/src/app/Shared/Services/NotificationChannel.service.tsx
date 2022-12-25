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
import { Notifications } from '@app/Notifications/Notifications';
import _ from 'lodash';
import { BehaviorSubject, combineLatest, Observable, Subject, timer } from 'rxjs';
import { fromFetch } from 'rxjs/fetch';
import { webSocket, WebSocketSubject } from 'rxjs/webSocket';
import { AlertVariant } from '@patternfly/react-core';
import { concatMap, distinctUntilChanged, filter } from 'rxjs/operators';
import { AuthMethod, LoginService, SessionState } from './Login.service';
import { Target } from './Target.service';
import { TargetDiscoveryEvent } from './Targets.service';

export enum NotificationCategory {
  WsClientActivity = 'WsClientActivity',
  TargetJvmDiscovery = 'TargetJvmDiscovery',
  ActiveRecordingCreated = 'ActiveRecordingCreated',
  ActiveRecordingStopped = 'ActiveRecordingStopped',
  ActiveRecordingSaved = 'ActiveRecordingSaved',
  ActiveRecordingDeleted = 'ActiveRecordingDeleted',
  SnapshotCreated = 'SnapshotCreated',
  SnapshotDeleted = 'SnapshotDeleted',
  ArchivedRecordingCreated = 'ArchivedRecordingCreated',
  ArchivedRecordingDeleted = 'ArchivedRecordingDeleted',
  TemplateUploaded = 'TemplateUploaded',
  TemplateDeleted = 'TemplateDeleted',
  ProbeTemplateUploaded = 'ProbeTemplateUploaded',
  ProbeTemplateDeleted = 'ProbeTemplateDeleted',
  ProbeTemplateApplied = 'ProbeTemplateApplied',
  ProbesRemoved = 'ProbesRemoved',
  RuleCreated = 'RuleCreated',
  RuleUpdated = 'RuleUpdated',
  RuleDeleted = 'RuleDeleted',
  RecordingMetadataUpdated = 'RecordingMetadataUpdated',
  GrafanaConfiguration = 'GrafanaConfiguration', // generated client-side
  TargetCredentialsStored = 'TargetCredentialsStored',
  TargetCredentialsDeleted = 'TargetCredentialsDeleted',
  CredentialsStored = 'CredentialsStored',
  CredentialsDeleted = 'CredentialsDeleted',
}

export enum CloseStatus {
  LOGGED_OUT = 1000,
  PROTOCOL_FAILURE = 1002,
  INTERNAL_ERROR = 1011,
  UNKNOWN = -1,
}

interface ReadyState {
  ready: boolean;
  code?: CloseStatus;
}

export const messageKeys = new Map([
  [
    // explicitly configure this category with a null message body mapper.
    // This is a special case because this is generated client-side,
    // not sent by the backend
    NotificationCategory.GrafanaConfiguration,
    {
      title: 'Grafana Configuration',
    },
  ],
  [
    NotificationCategory.TargetJvmDiscovery,
    {
      variant: AlertVariant.info,
      title: 'Target JVM Discovery',
      body: (v) => {
        const evt: TargetDiscoveryEvent = v.message.event;
        const target: Target = evt.serviceRef;
        switch (evt.kind) {
          case 'FOUND':
            return `Target "${target.alias}" appeared (${target.connectUrl})"`;
          case 'LOST':
            return `Target "${target.alias}" disappeared (${target.connectUrl})"`;
          default:
            return `Received a notification with category ${NotificationCategory.TargetJvmDiscovery} and unrecognized kind ${evt.kind}`;
        }
      },
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.WsClientActivity,
    {
      variant: AlertVariant.info,
      title: 'WebSocket Client Activity',
      body: (evt) => {
        const addr = Object.keys(evt.message)[0];
        const status = evt.message[addr];
        return `Client at ${addr} ${status}`;
      },
      hidden: true,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ActiveRecordingCreated,
    {
      variant: AlertVariant.success,
      title: 'Recording Created',
      body: (evt) => `${evt.message.recording.name} created in target: ${evt.message.target}`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ActiveRecordingStopped,
    {
      variant: AlertVariant.success,
      title: 'Recording Stopped',
      body: (evt) => `${evt.message.recording.name} was stopped`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ActiveRecordingSaved,
    {
      variant: AlertVariant.success,
      title: 'Recording Saved',
      body: (evt) => `${evt.message.recording.name} was archived`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ActiveRecordingDeleted,
    {
      variant: AlertVariant.success,
      title: 'Recording Deleted',
      body: (evt) => `${evt.message.recording.name} was deleted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.SnapshotCreated,
    {
      variant: AlertVariant.success,
      title: 'Snapshot Created',
      body: (evt) => `${evt.message.recording.name} was created in target: ${evt.message.target}`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.SnapshotDeleted,
    {
      variant: AlertVariant.success,
      title: 'Snapshot Deleted',
      body: (evt) => `${evt.message.recording.name} was deleted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ArchivedRecordingCreated,
    {
      variant: AlertVariant.success,
      title: 'Archived Recording Uploaded',
      body: (evt) => `${evt.message.recording.name} was uploaded into archives`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ArchivedRecordingDeleted,
    {
      variant: AlertVariant.success,
      title: 'Archived Recording Deleted',
      body: (evt) => `${evt.message.recording.name} was deleted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.TemplateUploaded,
    {
      variant: AlertVariant.success,
      title: 'Template Created',
      body: (evt) => `${evt.message.template.name} was created`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ProbeTemplateUploaded,
    {
      variant: AlertVariant.success,
      title: 'Probe Template Created',
      body: (evt) => `${evt.message.probeTemplate} was created`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ProbeTemplateApplied,
    {
      variant: AlertVariant.success,
      title: 'Probe Template Applied',
      body: (evt) => `${evt.message.probeTemplate} was inserted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.TemplateDeleted,
    {
      variant: AlertVariant.success,
      title: 'Template Deleted',
      body: (evt) => `${evt.message.template.name} was deleted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ProbeTemplateDeleted,
    {
      variant: AlertVariant.success,
      title: 'Probe Template Deleted',
      body: (evt) => `${evt.message.probeTemplate} was deleted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.ProbesRemoved,
    {
      variant: AlertVariant.success,
      title: 'Probes Removed from Target',
      body: (evt) => `Probes successfully removed from ${evt.message.target}`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.RuleCreated,
    {
      variant: AlertVariant.success,
      title: 'Automated Rule Created',
      body: (evt) => `${evt.message.name} was created`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.RuleUpdated,
    {
      variant: AlertVariant.success,
      title: 'Automated Rule Updated',
      body: (evt) => `${evt.message.name} was ` + (evt.message.enabled ? 'enabled' : 'disabled'),
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.RuleDeleted,
    {
      variant: AlertVariant.success,
      title: 'Automated Rule Deleted',
      body: (evt) => `${evt.message.name} was deleted`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.RecordingMetadataUpdated,
    {
      variant: AlertVariant.success,
      title: 'Recording Metadata Updated',
      body: (evt) => `${evt.message.recordingName} metadata was updated`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.TargetCredentialsStored,
    {
      variant: AlertVariant.success,
      title: 'Target Credentials Stored',
      body: (evt) => `Credentials stored for target: ${evt.message.target}`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.TargetCredentialsDeleted,
    {
      variant: AlertVariant.success,
      title: 'Target Credentials Deleted',
      body: (evt) => `Credentials deleted for target: ${evt.message.target}`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.CredentialsStored,
    {
      variant: AlertVariant.success,
      title: 'Credentials Stored',
      body: (evt) => `Credentials stored for: ${evt.message.matchExpression}`,
    } as NotificationMessageMapper,
  ],
  [
    NotificationCategory.CredentialsDeleted,
    {
      variant: AlertVariant.success,
      title: 'Credentials Deleted',
      body: (evt) => `Credentials deleted for: ${evt.message.matchExpression}`,
    } as NotificationMessageMapper,
  ],
]);

interface NotificationMessageMapper {
  title: string;
  body?: (evt: NotificationMessage) => string;
  variant?: AlertVariant;
  hidden?: boolean;
}

export class NotificationChannel {
  private ws: WebSocketSubject<any> | null = null;
  private readonly _messages = new Subject<NotificationMessage>();
  private readonly _ready = new BehaviorSubject<ReadyState>({ ready: false });

  constructor(private readonly notifications: Notifications, private readonly login: LoginService) {
    messageKeys.forEach((value, key) => {
      if (!value || !value.body || !value.variant) {
        return;
      }
      this.messages(key).subscribe((msg: NotificationMessage) => {
        if (!value || !value.body || !value.variant) {
          return;
        }
        const message = value.body(msg);
        notifications.notify({
          title: value.title,
          message,
          category: key,
          variant: value.variant,
          hidden: value.hidden,
        });
      });
    });

    // fallback handler for unknown categories of message
    this._messages
      .pipe(filter((msg) => !messageKeys.has(msg.meta.category as NotificationCategory)))
      .subscribe((msg) => {
        const category = NotificationCategory[msg.meta.category as keyof typeof NotificationCategory];
        notifications.notify({
          title: msg.meta.category,
          message: msg.message,
          category,
          variant: AlertVariant.success,
        });
      });

    const notificationsUrl = fromFetch(`${this.login.authority}/api/v1/notifications_url`).pipe(
      concatMap(async (resp) => {
        if (resp.ok) {
          let body: any = await resp.json();
          return body.notificationsUrl;
        } else {
          let body: string = await resp.text();
          throw new Error(resp.status + ' ' + body);
        }
      })
    );

    combineLatest([
      notificationsUrl,
      this.login.getToken(),
      this.login.getAuthMethod(),
      this.login.getSessionState(),
      timer(0, 5000),
    ])
      .pipe(distinctUntilChanged(_.isEqual))
      .subscribe({
        next: (parts: string[]) => {
          const url = parts[0];
          const token = parts[1];
          const authMethod = parts[2];
          const sessionState = parseInt(parts[3]);
          let subprotocol: string | undefined = undefined;

          if (sessionState !== SessionState.CREATING_USER_SESSION) {
            return;
          }

          if (authMethod === AuthMethod.BEARER) {
            subprotocol = `base64url.bearer.authorization.cryostat.${token}`;
          } else if (authMethod === AuthMethod.BASIC) {
            subprotocol = `basic.authorization.cryostat.${token}`;
          }

          if (!!this.ws) {
            this.ws.complete();
          }

          this.ws = webSocket({
            url,
            protocol: subprotocol,
            openObserver: {
              next: () => {
                this._ready.next({ ready: true });
                this.login.setSessionState(SessionState.USER_SESSION);
              },
            },
            closeObserver: {
              next: (evt) => {
                let code: CloseStatus;
                let msg: string | undefined = undefined;
                let fn: Function;
                let sessionState: SessionState;
                switch (evt.code) {
                  case CloseStatus.LOGGED_OUT:
                    code = CloseStatus.LOGGED_OUT;
                    msg = 'Logout success';
                    fn = this.notifications.info;
                    sessionState = SessionState.NO_USER_SESSION;
                    break;
                  case CloseStatus.PROTOCOL_FAILURE:
                    code = CloseStatus.PROTOCOL_FAILURE;
                    msg = 'Authentication failed';
                    fn = this.notifications.danger;
                    sessionState = SessionState.NO_USER_SESSION;
                    break;
                  case CloseStatus.INTERNAL_ERROR:
                    code = CloseStatus.INTERNAL_ERROR;
                    msg = 'Internal server error';
                    fn = this.notifications.danger;
                    sessionState = SessionState.CREATING_USER_SESSION;
                    break;
                  default:
                    code = CloseStatus.UNKNOWN;
                    fn = this.notifications.info;
                    sessionState = SessionState.CREATING_USER_SESSION;
                    break;
                }
                this._ready.next({ ready: false, code });
                this.login.setSessionState(sessionState);
                fn.apply(this.notifications, [
                  'WebSocket connection lost',
                  msg,
                  NotificationCategory.WsClientActivity,
                  fn === this.notifications.info,
                ]);
              },
            },
          });

          this.ws.subscribe({
            next: (v) => this._messages.next(v),
            error: (err) => this.logError('WebSocket error', err),
          });

          // message doesn't matter, we just need to send something to the server so that our SubProtocol token can be authenticated
          this.ws.next('connect');
        },
        error: (err: any) => this.logError('Notifications URL configuration', err),
      });

    this.login.loggedOut().subscribe({
      next: () => {
        this.ws?.complete();
      },
      error: (err: any) => this.logError('Notifications URL configuration', err),
    });
  }

  isReady(): Observable<ReadyState> {
    return this._ready.asObservable();
  }

  messages(category: string): Observable<NotificationMessage> {
    return this._messages.asObservable().pipe(filter((msg) => msg.meta.category === category));
  }

  private logError(title: string, err: any): void {
    window.console.error(err?.message);
    window.console.error(err?.stack);

    if (!!err?.message) {
      this.notifications.danger(title, JSON.stringify(err?.message));
    }
  }
}

export interface NotificationMessage {
  meta: MessageMeta;
  message: any;
  serverTime: number;
}

export interface MessageMeta {
  category: string;
  type: MessageType;
}

export interface MessageType {
  type: string;
  subtype: string;
}
