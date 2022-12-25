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
import * as _ from 'lodash';
import { ServiceContext } from '@app/Shared/Services/Services';
import { NotificationCenter } from '@app/Notifications/NotificationCenter';
import { IAppRoute, navGroups, routes } from '@app/routes';
import {
  Alert,
  AlertGroup,
  AlertVariant,
  AlertActionCloseButton,
  Brand,
  Button,
  Dropdown,
  DropdownGroup,
  DropdownItem,
  DropdownToggle,
  Nav,
  NavGroup,
  NavItem,
  NavList,
  NotificationBadge,
  Page,
  PageHeader,
  PageHeaderTools,
  PageHeaderToolsGroup,
  PageHeaderToolsItem,
  PageSidebar,
  SkipToContent,
  Label,
} from '@patternfly/react-core';
import { BellIcon, CaretDownIcon, CogIcon, HelpIcon, PlusCircleIcon, UserIcon } from '@patternfly/react-icons';
import { map } from 'rxjs/operators';
import { matchPath, NavLink, useHistory, useLocation } from 'react-router-dom';
import { Notification, NotificationsContext } from '@app/Notifications/Notifications';
import { AuthModal } from './AuthModal';
import { SslErrorModal } from './SslErrorModal';
import { AboutCryostatModal } from '@app/About/AboutCryostatModal';
import cryostatLogo from '@app/assets/cryostat_logo_hori_rgb_reverse.svg';
import { SessionState } from '@app/Shared/Services/Login.service';
import { NotificationCategory } from '@app/Shared/Services/NotificationChannel.service';
import { useSubscriptions } from '@app/utils/useSubscriptions';
import { FeatureLevel } from '@app/Shared/Services/Settings.service';
import { FeatureFlag } from '@app/Shared/FeatureFlag/FeatureFlag';

interface IAppLayout {
  children: React.ReactNode;
}

const AppLayout: React.FunctionComponent<IAppLayout> = ({ children }) => {
  const serviceContext = React.useContext(ServiceContext);
  const notificationsContext = React.useContext(NotificationsContext);
  const addSubscription = useSubscriptions();
  const routerHistory = useHistory();
  const logoProps = {
    href: '/',
  };
  const [isNavOpen, setIsNavOpen] = React.useState(true);
  const [isMobileView, setIsMobileView] = React.useState(true);
  const [isNavOpenMobile, setIsNavOpenMobile] = React.useState(false);
  const [showAuthModal, setShowAuthModal] = React.useState(false);
  const [showSslErrorModal, setShowSslErrorModal] = React.useState(false);
  const [aboutModalOpen, setAboutModalOpen] = React.useState(false);
  const [isNotificationDrawerExpanded, setNotificationDrawerExpanded] = React.useState(false);
  const [showUserIcon, setShowUserIcon] = React.useState(false);
  const [showUserInfoDropdown, setShowUserInfoDropdown] = React.useState(false);
  const [username, setUsername] = React.useState('');
  const [notifications, setNotifications] = React.useState([] as Notification[]);
  const [unreadNotificationsCount, setUnreadNotificationsCount] = React.useState(0);
  const [errorNotificationsCount, setErrorNotificationsCount] = React.useState(0);
  const location = useLocation();

  React.useEffect(() => {
    addSubscription(
      serviceContext.target.authFailure().subscribe(() => {
        setShowAuthModal(true);
      })
    );
  }, [serviceContext.target, setShowAuthModal, addSubscription]);

  React.useEffect(() => {
    addSubscription(notificationsContext.notifications().subscribe((n) => setNotifications([...n])));
  }, [notificationsContext.notifications, addSubscription]);

  React.useEffect(() => {
    addSubscription(notificationsContext.drawerState().subscribe(setNotificationDrawerExpanded));
  }, [addSubscription, notificationsContext.drawerState, setNotificationDrawerExpanded]);

  const notificationsToDisplay = React.useMemo(() => {
    return notifications
      .filter((n) => !n.read && !n.hidden)
      .filter((n) => serviceContext.settings.notificationsEnabledFor(NotificationCategory[n.category || '']))
      .sort((prev, curr) => {
        if (!prev.timestamp) return -1;
        if (!curr.timestamp) return 1;
        return prev.timestamp - curr.timestamp;
      });
  }, [notifications, serviceContext.settings, serviceContext.settings.notificationsEnabledFor]);

  const overflowMessage = React.useMemo(() => {
    if (isNotificationDrawerExpanded) {
      return '';
    }
    const overflow = notificationsToDisplay.length - serviceContext.settings.visibleNotificationsCount();
    if (overflow > 0) {
      return `View ${overflow} more`;
    }
    return '';
  }, [isNotificationDrawerExpanded, notificationsToDisplay, serviceContext.settings.visibleNotificationsCount]);

  React.useEffect(() => {
    addSubscription(notificationsContext.unreadNotifications().subscribe((s) => setUnreadNotificationsCount(s.length)));
  }, [
    notificationsContext.unreadNotifications,
    unreadNotificationsCount,
    setUnreadNotificationsCount,
    addSubscription,
  ]);

  React.useEffect(() => {
    addSubscription(
      notificationsContext
        .unreadNotifications()
        .pipe(
          map((notifications: Notification[]) =>
            _.filter(notifications, (n) => n.variant === AlertVariant.danger || n.variant === AlertVariant.warning)
          )
        )
        .subscribe((s) => setErrorNotificationsCount(s.length))
    );
  }, [
    notificationsContext,
    notificationsContext.unreadNotifications,
    unreadNotificationsCount,
    setUnreadNotificationsCount,
    addSubscription,
  ]);

  const dismissAuthModal = React.useCallback(() => {
    setShowAuthModal(false);
  }, [setShowAuthModal]);

  const authModalOnSave = React.useCallback(() => {
    serviceContext.target.setAuthRetry();
    dismissAuthModal();
  }, [serviceContext.target, dismissAuthModal]);

  const handleMarkNotificationRead = React.useCallback(
    (key) => () => notificationsContext.setRead(key, true),
    [notificationsContext.setRead]
  );

  const handleTimeout = React.useCallback(
    (key) => () => notificationsContext.setHidden(key),
    [notificationsContext.setHidden]
  );

  React.useEffect(() => {
    addSubscription(
      serviceContext.target.sslFailure().subscribe(() => {
        setShowSslErrorModal(true);
      })
    );
  }, [serviceContext.target, serviceContext.target.sslFailure, setShowSslErrorModal, addSubscription]);

  const dismissSslErrorModal = React.useCallback(() => setShowSslErrorModal(false), [setShowSslErrorModal]);

  const onNavToggleMobile = React.useCallback(() => {
    setIsNavOpenMobile((isNavOpenMobile) => !isNavOpenMobile);
  }, [setIsNavOpenMobile]);

  const onNavToggle = React.useCallback(() => {
    setIsNavOpen((isNavOpen) => !isNavOpen);
  }, [setIsNavOpen]);

  const onPageResize = React.useCallback(
    (props: { mobileView: boolean; windowSize: number }) => {
      setIsMobileView(props.mobileView);
    },
    [setIsMobileView]
  );

  const mobileOnSelect = React.useCallback(
    (selected) => {
      if (isMobileView) {
        setIsNavOpenMobile(false);
      }
    },
    [isMobileView, setIsNavOpenMobile]
  );

  const handleSettingsButtonClick = React.useCallback(() => {
    routerHistory.push('/settings');
  }, [routerHistory.push]);

  const handleNotificationCenterToggle = React.useCallback(() => {
    notificationsContext.setDrawerState(!isNotificationDrawerExpanded);
  }, [isNotificationDrawerExpanded, notificationsContext.setDrawerState]);

  const handleCloseNotificationCenter = React.useCallback(() => {
    notificationsContext.setDrawerState(false);
  }, [notificationsContext.setDrawerState]);

  const handleOpenNotificationCenter = React.useCallback(() => {
    notificationsContext.setDrawerState(true);
  }, [notificationsContext.setDrawerState]);

  const handleAboutModalToggle = React.useCallback(() => {
    setAboutModalOpen((aboutModalOpen) => !aboutModalOpen);
  }, [setAboutModalOpen]);

  React.useEffect(() => {
    addSubscription(
      serviceContext.login.getSessionState().subscribe((sessionState) => {
        setShowUserIcon(sessionState === SessionState.USER_SESSION);
      })
    );
  }, [serviceContext.login, serviceContext.login.getSessionState, setShowUserIcon, addSubscription]);

  const handleLogout = React.useCallback(() => {
    addSubscription(serviceContext.login.setLoggedOut().subscribe());
  }, [serviceContext.login, serviceContext.login.setLoggedOut, addSubscription]);

  const handleUserInfoToggle = React.useCallback(() => setShowUserInfoDropdown((v) => !v), [setShowUserInfoDropdown]);

  React.useEffect(() => {
    addSubscription(serviceContext.login.getUsername().subscribe(setUsername));
  }, [serviceContext, serviceContext.login, addSubscription, setUsername]);

  const userInfoItems = [
    <DropdownGroup key={0}>
      <DropdownItem onClick={handleLogout}>Logout</DropdownItem>
    </DropdownGroup>,
  ];

  const UserInfoToggle = (
    <DropdownToggle onToggle={handleUserInfoToggle} toggleIndicator={CaretDownIcon}>
      {username || <UserIcon color="white" size="sm" />}
    </DropdownToggle>
  );

  // TODO refactor to use Masthead, Toolbar components: https://www.patternfly.org/v4/components/page
  const HeaderTools = (
    <>
      <PageHeaderTools>
        <PageHeaderToolsGroup>
          <FeatureFlag level={FeatureLevel.DEVELOPMENT}>
            <PageHeaderToolsItem>
              <Button
                variant="link"
                onClick={() => notificationsContext.info(`test ${+Date.now()}`)}
                icon={<PlusCircleIcon color="white" size="sm" />}
              />
            </PageHeaderToolsItem>
          </FeatureFlag>
          <PageHeaderToolsItem visibility={{ default: 'visible' }} isSelected={isNotificationDrawerExpanded}>
            <NotificationBadge
              count={unreadNotificationsCount}
              variant={errorNotificationsCount > 0 ? 'attention' : unreadNotificationsCount === 0 ? 'read' : 'unread'}
              onClick={handleNotificationCenterToggle}
              aria-label="Notifications"
            >
              <BellIcon />
            </NotificationBadge>
          </PageHeaderToolsItem>
          <PageHeaderToolsItem>
            <Button onClick={handleSettingsButtonClick} variant="link" icon={<CogIcon color="white " size="sm" />} />
          </PageHeaderToolsItem>
          <PageHeaderToolsItem>
            <Button onClick={handleAboutModalToggle} variant="link" icon={<HelpIcon color="white" size="sm" />} />
          </PageHeaderToolsItem>
          <PageHeaderToolsItem visibility={{ default: showUserIcon ? 'visible' : 'hidden' }}>
            <Dropdown
              isPlain={true}
              isOpen={showUserInfoDropdown}
              toggle={UserInfoToggle}
              dropdownItems={userInfoItems}
            />
          </PageHeaderToolsItem>
        </PageHeaderToolsGroup>
      </PageHeaderTools>
    </>
  );

  const Header = (
    <>
      <PageHeader
        logo={
          <>
            <Brand alt="Cryostat" src={cryostatLogo} className="cryostat-logo" />
            <FeatureFlag strict level={FeatureLevel.DEVELOPMENT}>
              <PageHeaderToolsItem>
                <Label isCompact color="red">
                  Development
                </Label>
              </PageHeaderToolsItem>
            </FeatureFlag>
            <FeatureFlag strict level={FeatureLevel.BETA}>
              <PageHeaderToolsItem>
                <Label isCompact color="green">
                  Beta
                </Label>
              </PageHeaderToolsItem>
            </FeatureFlag>
          </>
        }
        logoProps={logoProps}
        showNavToggle
        isNavOpen={isNavOpen}
        onNavToggle={isMobileView ? onNavToggleMobile : onNavToggle}
        headerTools={HeaderTools}
      />
      <AboutCryostatModal isOpen={aboutModalOpen} onClose={handleAboutModalToggle} />
    </>
  );

  const isActiveRoute = (route: IAppRoute): boolean => {
    const match = matchPath(location.pathname, route.path);
    if (match && match.isExact) {
      return true;
    } else if (route.children) {
      let childMatch = false;
      for (const r of route.children) {
        childMatch = childMatch || isActiveRoute(r);
      }
      return childMatch;
    }
    return false;
  };

  const Navigation = (
    <Nav id="nav-primary-simple" theme="dark" variant="default" onSelect={mobileOnSelect}>
      <NavList id="nav-list-simple">
        {navGroups.map((title) => {
          return (
            <NavGroup title={title} key={title}>
              {routes
                .filter((route) => route.navGroup === title)
                .map((route, idx) => {
                  return (
                    route.label && (
                      <NavItem
                        key={`${route.label}-${idx}`}
                        id={`${route.label}-${idx}`}
                        isActive={isActiveRoute(route)}
                      >
                        <NavLink exact to={route.path} activeClassName="pf-m-current">
                          {route.label}
                        </NavLink>
                      </NavItem>
                    )
                  );
                })}
            </NavGroup>
          );
        })}
      </NavList>
    </Nav>
  );

  const Sidebar = <PageSidebar theme="dark" nav={Navigation} isNavOpen={isMobileView ? isNavOpenMobile : isNavOpen} />;
  const PageSkipToContent = <SkipToContent href="#primary-app-container">Skip to Content</SkipToContent>;
  const NotificationDrawer = React.useMemo(() => <NotificationCenter onClose={handleCloseNotificationCenter} />, []);
  return (
    <>
      <AlertGroup isToast isLiveRegion overflowMessage={overflowMessage} onOverflowClick={handleOpenNotificationCenter}>
        {notificationsToDisplay
          .slice(0, serviceContext.settings.visibleNotificationsCount())
          .map(({ key, title, message, variant }) => (
            <Alert
              isLiveRegion
              variant={variant}
              key={title}
              title={title}
              actionClose={<AlertActionCloseButton onClose={handleMarkNotificationRead(key)} />}
              timeout={true}
              onTimeout={handleTimeout(key)}
            >
              {message?.toString()}
            </Alert>
          ))}
      </AlertGroup>
      <Page
        mainContainerId="primary-app-container"
        header={Header}
        sidebar={Sidebar}
        notificationDrawer={NotificationDrawer}
        isNotificationDrawerExpanded={isNotificationDrawerExpanded}
        onPageResize={onPageResize}
        skipToContent={PageSkipToContent}
      >
        {children}
      </Page>
      <AuthModal visible={showAuthModal} onDismiss={dismissAuthModal} onSave={authModalOnSave} />
      <SslErrorModal visible={showSslErrorModal} onDismiss={dismissSslErrorModal} />
    </>
  );
};

export { AppLayout };
