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
import { NotificationsInstance } from '@app/Notifications/Notifications';
import { TargetService, TargetInstance } from './Target.service';
import { TargetsService } from './Targets.service';
import { ApiService } from './Api.service';
import { NotificationChannel } from './NotificationChannel.service';
import { ReportService } from './Report.service';
import { SettingsService } from './Settings.service';
import { LoginService } from './Login.service';
import { JmxCredentials } from './JmxCredentials.service';

export interface Services {
  target: TargetService;
  targets: TargetsService;
  api: ApiService;
  jmxCredentials: JmxCredentials;
  notificationChannel: NotificationChannel;
  reports: ReportService;
  settings: SettingsService;
  login: LoginService;
}

const settings = new SettingsService();
const jmxCredentials = new JmxCredentials(() => api);
const login = new LoginService(TargetInstance, jmxCredentials, settings);
const api = new ApiService(TargetInstance, NotificationsInstance, login);
const notificationChannel = new NotificationChannel(NotificationsInstance, login);
const reports = new ReportService(login, NotificationsInstance);
const targets = new TargetsService(api, NotificationsInstance, login, notificationChannel);

const defaultServices: Services = {
  target: TargetInstance,
  targets,
  api,
  jmxCredentials,
  notificationChannel,
  reports,
  settings,
  login,
};

const ServiceContext: React.Context<Services> = React.createContext(defaultServices);

export { ServiceContext, defaultServices };
