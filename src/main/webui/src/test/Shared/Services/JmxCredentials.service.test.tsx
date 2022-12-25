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
import { JmxCredentials } from '@app/Shared/Services/JmxCredentials.service';
import { ApiService } from '@app/Shared/Services/Api.service';
import { firstValueFrom, Observable, of } from 'rxjs';
import { getFromLocalStorage, saveToLocalStorage } from '@app/utils/LocalStorage';

jest.mock('@app/utils/LocalStorage', () => {
  const map = new Map<any, any>();
  return {
    getFromLocalStorage: jest.fn((key: any, defaultValue: any): any => {
      if (map.has(key)) {
        return map.get(key);
      }
      return defaultValue;
    }),

    saveToLocalStorage: jest.fn((key: any, value: any): any => {
      map.set(key, value);
    }),
  };
});

const mockApi = {
  postCredentials: (expr: string, user: string, pass: string): Observable<boolean> => of(true),
} as ApiService;
const postCredentialsSpy = jest.spyOn(mockApi, 'postCredentials');

describe('JmxCredentials.service', () => {
  let svc: JmxCredentials;
  beforeEach(() => {
    saveToLocalStorage('JMX_CREDENTIAL_LOCATION', undefined);
    postCredentialsSpy.mockReset();
    svc = new JmxCredentials(() => mockApi);
  });

  it('should not check storage on instantiation', () => {
    expect(getFromLocalStorage).not.toHaveBeenCalled();
  });

  describe('with invalid location selected in storage', () => {
    beforeEach(() => {
      saveToLocalStorage('JMX_CREDENTIAL_LOCATION', 'BAD_LOCATION');
    });

    it('retrieves undefined credentials from memory map', async () => {
      const cred = await firstValueFrom(svc.getCredential('myTarget'));
      expect(getFromLocalStorage).toHaveBeenCalled();
      expect(cred).toBeFalsy();
      expect(postCredentialsSpy).not.toHaveBeenCalled();
    });

    it('retrieves previously defined credentials from memory map', async () => {
      svc.setCredential('myTarget', 'foouser', 'foopass');
      expect(getFromLocalStorage).toHaveBeenCalledTimes(1);
      const cred = await firstValueFrom(svc.getCredential('myTarget'));
      expect(getFromLocalStorage).toHaveBeenCalledTimes(2);
      expect(cred).toBeUndefined();
      expect(postCredentialsSpy).not.toHaveBeenCalled();
    });
  });

  describe('with session selected in storage', () => {
    beforeEach(() => {
      saveToLocalStorage('JMX_CREDENTIAL_LOCATION', 'Session (Browser Memory)');
    });

    it('retrieves undefined credentials from memory map', async () => {
      const cred = await firstValueFrom(svc.getCredential('myTarget'));
      expect(getFromLocalStorage).toHaveBeenCalledTimes(1);
      expect(cred).toBeUndefined();
      expect(postCredentialsSpy).not.toHaveBeenCalled();
    });

    it('retrieves previously defined credentials from memory map', async () => {
      svc.setCredential('myTarget', 'foouser', 'foopass');
      expect(getFromLocalStorage).toHaveBeenCalledTimes(1);
      const cred = await firstValueFrom(svc.getCredential('myTarget'));
      expect(getFromLocalStorage).toHaveBeenCalledTimes(2);
      expect(cred).toBeDefined();
      expect(cred?.username).toEqual('foouser');
      expect(cred?.password).toEqual('foopass');
      expect(postCredentialsSpy).not.toHaveBeenCalled();
    });
  });

  describe('with backend selected in storage', () => {
    beforeEach(() => {
      saveToLocalStorage('JMX_CREDENTIAL_LOCATION', 'Backend');
    });

    it('does not retrieve credentials', async () => {
      const cred = await firstValueFrom(svc.getCredential('myTarget'));
      expect(cred).toBeUndefined();
      expect(postCredentialsSpy).not.toHaveBeenCalled();
    });

    it('POSTs credentials to API service', async () => {
      svc.setCredential('myTarget', 'foouser', 'foopass');
      expect(postCredentialsSpy).toHaveBeenCalled();
    });
  });
});
