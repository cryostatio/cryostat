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

import React, { PropsWithChildren } from 'react';
import { render } from '@testing-library/react';
import { Provider } from 'react-redux';
import { setupStore } from '@app/Shared/Redux/ReduxStore';
import { defaultServices, ServiceContext } from '@app/Shared/Services/Services';
import { NotificationsContext, NotificationsInstance } from '@app/Notifications/Notifications';
import { Router } from 'react-router-dom';
import { createMemoryHistory } from 'history';
// userEvent functions are recommended to be called in tests (i.e it()).
// See https://testing-library.com/docs/user-event/intro#writing-tests-with-userevent
import userEvent from '@testing-library/user-event';

export const renderDefault = (
  ui: React.ReactElement,
  {
    user = userEvent.setup(), // Create a default user session
    notifications = NotificationsInstance,
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return <NotificationsContext.Provider value={notifications}>{children}</NotificationsContext.Provider>;
  };
  return { user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};

export const renderWithReduxStore = (
  ui: React.ReactElement,
  {
    preloadState = {},
    store = setupStore(preloadState), // Create a new store instance if no store was passed in
    user = userEvent.setup(), // Create a default user session
    notifications = NotificationsInstance,
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return (
      <NotificationsContext.Provider value={notifications}>
        <Provider store={store}>{children}</Provider>
      </NotificationsContext.Provider>
    );
  };
  return { store, user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};

export const renderWithServiceContext = (
  ui: React.ReactElement,
  {
    services = defaultServices,
    notifications = NotificationsInstance,
    user = userEvent.setup(), // Create a default user session
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return (
      <ServiceContext.Provider value={services}>
        <NotificationsContext.Provider value={notifications}>{children}</NotificationsContext.Provider>
      </ServiceContext.Provider>
    );
  };
  return { user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};

export const renderWithRouter = (
  ui: React.ReactElement,
  {
    history = createMemoryHistory({ initialEntries: ['/'] }),
    user = userEvent.setup(), // Create a default user session
    notifications = NotificationsInstance,
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return (
      <NotificationsContext.Provider value={notifications}>
        <Router location={history.location} history={history}>
          {children}
        </Router>
      </NotificationsContext.Provider>
    );
  };
  return { user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};

export const renderWithServiceContextAndReduxStore = (
  ui: React.ReactElement,
  {
    preloadState = {},
    store = setupStore(preloadState), // Create a new store instance if no store was passed in
    services = defaultServices,
    notifications = NotificationsInstance,
    user = userEvent.setup(), // Create a default user session
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return (
      <ServiceContext.Provider value={services}>
        <NotificationsContext.Provider value={notifications}>
          <Provider store={store}>{children}</Provider>
        </NotificationsContext.Provider>
      </ServiceContext.Provider>
    );
  };
  return { store, user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};

export const renderWithServiceContextAndRouter = (
  ui: React.ReactElement,
  {
    services = defaultServices,
    notifications = NotificationsInstance,
    user = userEvent.setup(), // Create a default user session
    history = createMemoryHistory({ initialEntries: ['/'] }),
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return (
      <ServiceContext.Provider value={services}>
        <NotificationsContext.Provider value={notifications}>
          <Router location={history.location} history={history}>
            {children}
          </Router>
        </NotificationsContext.Provider>
      </ServiceContext.Provider>
    );
  };
  return { user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};

export const renderWithServiceContextAndReduxStoreWithRouter = (
  ui: React.ReactElement,
  {
    preloadState = {},
    store = setupStore(preloadState), // Create a new store instance if no store was passed in
    services = defaultServices,
    notifications = NotificationsInstance,
    user = userEvent.setup(), // Create a default user session
    history = createMemoryHistory({ initialEntries: ['/'] }),
    ...renderOptions
  } = {}
) => {
  const Wrapper = ({ children }: PropsWithChildren<{}>) => {
    return (
      <ServiceContext.Provider value={services}>
        <NotificationsContext.Provider value={notifications}>
          <Provider store={store}>
            <Router location={history.location} history={history}>
              {children}
            </Router>
          </Provider>
        </NotificationsContext.Provider>
      </ServiceContext.Provider>
    );
  };
  return { store, user, ...render(ui, { wrapper: Wrapper, ...renderOptions }) };
};
