/*
 * Copyright The Cryostat Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.cryostat.audit;

import io.cryostat.security.UserInfoResolver;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.envers.RevisionListener;
import org.jboss.logging.Logger;

public class RevisionInfoListener implements RevisionListener {

    private static final Logger logger = Logger.getLogger(RevisionInfoListener.class);
    private static final int MAX_USERNAME_LENGTH = 64;

    @Override
    public void newRevision(Object revisionEntity) {
        if (!(revisionEntity instanceof RevisionInfo revInfo)) {
            logger.debugf("Expected RevisionInfo but got %s", revisionEntity.getClass().getName());
            return;
        }

        String username = UserInfoResolver.resolveUsername();

        if (StringUtils.isNotBlank(username)) {
            // Truncate if longer than max length (e.g., JWT tokens)
            if (username.length() > MAX_USERNAME_LENGTH) {
                logger.debugf(
                        "Truncating username from %d to %d characters",
                        username.length(), MAX_USERNAME_LENGTH);
                username = username.substring(0, MAX_USERNAME_LENGTH);
            }
            revInfo.setUsername(username);
            logger.debugf("Revision %d created by user: %s", revInfo.getId(), username);
        } else {
            logger.debugf("Revision %d created without user context", revInfo.getId());
        }
    }
}
