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
import {
  Dropdown,
  DropdownItem,
  DropdownPosition,
  KebabToggle,
  NotificationDrawer,
  NotificationDrawerBody,
  NotificationDrawerGroup,
  NotificationDrawerGroupList,
  NotificationDrawerHeader,
  NotificationDrawerList,
  NotificationDrawerListItem,
  NotificationDrawerListItemBody,
  NotificationDrawerListItemHeader,
  Text,
  TextVariants,
} from '@patternfly/react-core';
import { Notification, NotificationsContext } from './Notifications';
import { combineLatest } from 'rxjs';

export interface NotificationCenterProps {
  onClose: () => void;
}

export interface NotificationDrawerCategory {
  title: string;
  isExpanded: boolean;
  notifications: Notification[];
  unreadCount: number;
}

export const NotificationCenter: React.FunctionComponent<NotificationCenterProps> = (props) => {
  const context = React.useContext(NotificationsContext);
  const [totalUnreadNotificationsCount, setTotalUnreadNotificationsCount] = React.useState(0);
  const [isHeaderDropdownOpen, setHeaderDropdownOpen] = React.useState(false);
  const PROBLEMS_CATEGORY_IDX = 2;
  const [drawerCategories, setDrawerCategories] = React.useState([
    { title: 'Completed Actions', isExpanded: true, notifications: [] as Notification[], unreadCount: 0 },
    { title: 'Cryostat Status', isExpanded: false, notifications: [] as Notification[], unreadCount: 0 },
    { title: 'Problems', isExpanded: false, notifications: [] as Notification[], unreadCount: 0 },
  ] as NotificationDrawerCategory[]);

  const countUnreadNotifications = (notifications: Notification[]) => {
    return notifications.filter((n) => !n.read).length;
  };

  React.useEffect(() => {
    const sub = combineLatest([
      context.actionsNotifications(),
      context.cryostatStatusNotifications(),
      context.problemsNotifications(),
    ]).subscribe((notificationLists) => {
      setDrawerCategories((drawerCategories) => {
        return drawerCategories.map((category: NotificationDrawerCategory, idx) => {
          category.notifications = notificationLists[idx];
          category.unreadCount = countUnreadNotifications(notificationLists[idx]);
          return category;
        });
      });
    });
    return () => sub.unsubscribe();
  }, [context, context.notifications, setDrawerCategories]);

  React.useEffect(() => {
    const sub = context.unreadNotifications().subscribe((s) => {
      setTotalUnreadNotificationsCount(s.length);
    });
    return () => sub.unsubscribe();
  }, [context, context.unreadNotifications, setTotalUnreadNotificationsCount]);

  const handleToggleDropdown = React.useCallback(() => {
    setHeaderDropdownOpen((v) => !v);
  }, [setHeaderDropdownOpen]);

  const handleToggleExpandCategory = React.useCallback(
    (categoryIdx) => {
      setDrawerCategories((drawerCategories) => {
        return drawerCategories.map((category: NotificationDrawerCategory, idx) => {
          category.isExpanded = idx === categoryIdx ? !category.isExpanded : false;
          return category;
        });
      });
    },
    [setDrawerCategories]
  );

  // Expands the Problems tab when unread errors/warnings are present
  React.useEffect(() => {
    if (drawerCategories[PROBLEMS_CATEGORY_IDX].unreadCount === 0) {
      return;
    }

    setDrawerCategories((drawerCategories) => {
      return drawerCategories.map((category: NotificationDrawerCategory, idx) => {
        category.isExpanded = idx === PROBLEMS_CATEGORY_IDX;
        return category;
      });
    });
  }, [setDrawerCategories, drawerCategories[PROBLEMS_CATEGORY_IDX].unreadCount]);

  const handleMarkAllRead = React.useCallback(() => {
    context.markAllRead();
  }, [context, context.markAllRead]);

  const handleClearAll = React.useCallback(() => {
    context.clearAll();
  }, [context, context.clearAll]);

  const markRead = React.useCallback(
    (key?: string) => {
      context.setRead(key);
    },
    [context, context.setRead]
  );

  const timestampToDateTimeString = (timestamp?: number): string => {
    if (!timestamp) {
      return '';
    }
    const date = new Date(timestamp);
    return `${date.toLocaleDateString()} ${date.toLocaleTimeString()}`;
  };

  const drawerDropdownItems = [
    <DropdownItem key="markAllRead" onClick={handleMarkAllRead} component="button">
      Mark all read
    </DropdownItem>,
    <DropdownItem key="clearAll" onClick={handleClearAll} component="button">
      Clear all
    </DropdownItem>,
  ];

  return (
    <>
      <NotificationDrawer>
        <NotificationDrawerHeader count={totalUnreadNotificationsCount} onClose={props.onClose}>
          <Dropdown
            isPlain
            onSelect={handleToggleDropdown}
            toggle={<KebabToggle onToggle={handleToggleDropdown} />}
            isOpen={isHeaderDropdownOpen}
            position={DropdownPosition.right}
            dropdownItems={drawerDropdownItems}
          />
        </NotificationDrawerHeader>
        <NotificationDrawerBody>
          <NotificationDrawerGroupList>
            {drawerCategories.map(({ title, isExpanded, notifications, unreadCount }, idx) => (
              <NotificationDrawerGroup
                title={title}
                isExpanded={isExpanded}
                count={unreadCount}
                onExpand={() => handleToggleExpandCategory(idx)}
                key={idx}
              >
                <NotificationDrawerList isHidden={!isExpanded}>
                  {notifications.map(({ key, title, message, variant, timestamp, read }) => (
                    <NotificationDrawerListItem key={key} variant={variant} onClick={() => markRead(key)} isRead={read}>
                      <NotificationDrawerListItemHeader title={title} variant={variant} />
                      <NotificationDrawerListItemBody timestamp={timestampToDateTimeString(timestamp)}>
                        <Text component={TextVariants.p}>{message?.toString()}</Text>
                      </NotificationDrawerListItemBody>
                    </NotificationDrawerListItem>
                  ))}
                </NotificationDrawerList>
              </NotificationDrawerGroup>
            ))}
          </NotificationDrawerGroupList>
        </NotificationDrawerBody>
      </NotificationDrawer>
    </>
  );
};
