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
import { Observable, Subject, BehaviorSubject } from 'rxjs';

export const NO_TARGET = {} as Target;

export const includesTarget = (arr: Target[], target: Target): boolean => {
  return arr.some((t) => t.connectUrl === target.connectUrl);
};

export const isEqualTarget = (a: Target, b: Target): boolean => {
  return a.connectUrl === b.connectUrl;
};

export const indexOfTarget = (arr: Target[], target: Target): number => {
  let index = -1;
  arr.forEach((t, idx) => {
    if (t.connectUrl === target.connectUrl) {
      index = idx;
    }
  });
  return index;
};

export interface Target {
  jvmId?: string; // present in responses, but we do not need to provide it in requests
  connectUrl: string;
  alias: string;
  labels?: {};
  annotations?: {
    cryostat: {};
    platform: {};
  };
}

class TargetService {
  private readonly _target: Subject<Target> = new BehaviorSubject(NO_TARGET);
  private readonly _authFailure: Subject<void> = new Subject();
  private readonly _authRetry: Subject<void> = new Subject();
  private readonly _sslFailure: Subject<void> = new Subject();

  setTarget(target: Target): void {
    if (target === NO_TARGET || !!target.connectUrl) {
      this._target.next(target);
    } else {
      throw new Error('Malformed target');
    }
  }

  target(): Observable<Target> {
    return this._target.asObservable();
  }

  authFailure(): Observable<void> {
    return this._authFailure.asObservable();
  }

  setAuthFailure(): void {
    this._authFailure.next();
  }

  authRetry(): Observable<void> {
    return this._authRetry.asObservable();
  }

  setAuthRetry(): void {
    this._authRetry.next();
  }

  sslFailure(): Observable<void> {
    return this._sslFailure.asObservable();
  }

  setSslFailure(): void {
    this._sslFailure.next();
  }
}

const TargetInstance = new TargetService();

export { TargetService, TargetInstance };
