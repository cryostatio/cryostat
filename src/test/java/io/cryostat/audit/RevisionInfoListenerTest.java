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

import static org.junit.jupiter.api.Assertions.*;

import io.cryostat.security.UserInfoResolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RevisionInfoListenerTest {

    private RevisionInfoListener listener;
    private RevisionInfo revisionInfo;

    @BeforeEach
    void setUp() {
        listener = new RevisionInfoListener();
        revisionInfo = new RevisionInfo();
        revisionInfo.setId(1);
    }

    @Test
    void shouldSetUsernameWhenUsernameIsAvailable() {
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn("testuser");

            listener.newRevision(revisionInfo);

            assertEquals("testuser", revisionInfo.getUsername());
        }
    }

    @Test
    void shouldNotSetUsernameWhenUsernameIsNull() {
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(null);

            listener.newRevision(revisionInfo);

            assertNull(revisionInfo.getUsername());
        }
    }

    @Test
    void shouldNotSetUsernameWhenUsernameIsEmpty() {
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn("");

            listener.newRevision(revisionInfo);

            assertNull(revisionInfo.getUsername());
        }
    }

    @Test
    void shouldNotSetUsernameWhenUsernameIsBlank() {
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn("   ");

            listener.newRevision(revisionInfo);

            assertNull(revisionInfo.getUsername());
        }
    }

    @Test
    void shouldTruncateUsernameWhenLengthEqualsMaxLength() {
        String longUsername = "a".repeat(255);
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(longUsername);

            listener.newRevision(revisionInfo);

            assertEquals(254, revisionInfo.getUsername().length());
            assertEquals("a".repeat(254), revisionInfo.getUsername());
        }
    }

    @Test
    void shouldTruncateUsernameWhenLengthExceedsMaxLength() {
        String longUsername = "a".repeat(300);
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(longUsername);

            listener.newRevision(revisionInfo);

            assertEquals(254, revisionInfo.getUsername().length());
            assertEquals("a".repeat(254), revisionInfo.getUsername());
        }
    }

    @Test
    void shouldNotTruncateUsernameWhenLengthIsJustBelowMaxLength() {
        String username = "a".repeat(254);
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(username);

            listener.newRevision(revisionInfo);

            assertEquals(254, revisionInfo.getUsername().length());
            assertEquals(username, revisionInfo.getUsername());
        }
    }

    @Test
    void shouldHandleUsernameWithSpecialCharacters() {
        String username = "user@example.com";
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(username);

            listener.newRevision(revisionInfo);

            assertEquals(username, revisionInfo.getUsername());
        }
    }

    @Test
    void shouldHandleUsernameWithUnicodeCharacters() {
        String username = "用户名";
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(username);

            listener.newRevision(revisionInfo);

            assertEquals(username, revisionInfo.getUsername());
        }
    }

    @Test
    void shouldHandleJwtTokenAsUsername() {
        String jwtToken =
                "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9."
                    + "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ."
                    + "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
                        + "a".repeat(50);
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(jwtToken);

            listener.newRevision(revisionInfo);

            assertEquals(254, revisionInfo.getUsername().length());
            assertTrue(
                    revisionInfo.getUsername().startsWith("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"));
        }
    }

    @Test
    void shouldDoNothingWhenRevisionEntityIsNotRevisionInfo() {
        Object wrongEntity = new Object();
        assertDoesNotThrow(() -> listener.newRevision(wrongEntity));
    }

    @Test
    void shouldHandleNullRevisionEntity() {
        assertDoesNotThrow(() -> listener.newRevision(null));
    }

    @Test
    void shouldPreserveExistingRevisionId() {
        revisionInfo.setId(42);
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn("testuser");

            listener.newRevision(revisionInfo);

            assertEquals(42, revisionInfo.getId());
            assertEquals("testuser", revisionInfo.getUsername());
        }
    }

    @Test
    void shouldHandleMultipleConsecutiveCalls() {
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn("user1");

            RevisionInfo rev1 = new RevisionInfo();
            rev1.setId(1);
            listener.newRevision(rev1);
            assertEquals("user1", rev1.getUsername());

            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn("user2");

            RevisionInfo rev2 = new RevisionInfo();
            rev2.setId(2);
            listener.newRevision(rev2);
            assertEquals("user2", rev2.getUsername());

            assertEquals("user1", rev1.getUsername());
        }
    }

    @Test
    void shouldHandleExceptionFromUserInfoResolver() {
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver
                    .when(UserInfoResolver::resolveUsername)
                    .thenThrow(new RuntimeException("Test exception"));

            assertThrows(RuntimeException.class, () -> listener.newRevision(revisionInfo));
        }
    }

    @Test
    void shouldTruncateAtExactlyMaxLengthMinusOne() {
        String username = "a".repeat(255);
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(username);

            listener.newRevision(revisionInfo);

            assertEquals(254, revisionInfo.getUsername().length());
            assertNotEquals(username, revisionInfo.getUsername());
        }
    }

    @Test
    void shouldHandleUsernameWithWhitespacePrefix() {
        String username = "  testuser";
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(username);

            listener.newRevision(revisionInfo);

            assertEquals(username, revisionInfo.getUsername());
        }
    }

    @Test
    void shouldHandleUsernameWithWhitespaceSuffix() {
        String username = "testuser  ";
        try (MockedStatic<UserInfoResolver> mockedResolver =
                Mockito.mockStatic(UserInfoResolver.class)) {
            mockedResolver.when(UserInfoResolver::resolveUsername).thenReturn(username);

            listener.newRevision(revisionInfo);

            assertEquals(username, revisionInfo.getUsername());
        }
    }
}
