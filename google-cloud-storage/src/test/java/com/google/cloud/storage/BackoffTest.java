/*
 * Copyright 2024 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.storage;

import static com.google.common.truth.Truth.assertThat;
import static java.time.Duration.ZERO;
import static org.junit.Assert.assertThrows;

import com.google.cloud.storage.Backoff.BackoffDuration;
import com.google.cloud.storage.Backoff.BackoffResult;
import com.google.cloud.storage.Backoff.BackoffResults;
import com.google.cloud.storage.Backoff.Jitterer;
import java.time.Duration;
import org.junit.Test;

public final class BackoffTest {

  @Test
  public void interruptedBackoffOnlyAddsActualElapsedTimeToCumulative() {
    Backoff backoff =
        Backoff.newBuilder()
            .setInitialBackoff(Duration.ofSeconds(2))
            .setMaxBackoff(Duration.ofSeconds(11))
            // this value is critical, if instead it is 35 seconds, the test can still succeed
            // even if the interrupted backoff duration isn't corrected
            .setTimeout(Duration.ofSeconds(34))
            .setJitterer(Jitterer.noJitter())
            .setRetryDelayMultiplier(2.0)
            .build();

    // operation failed after 1s
    BackoffResult r1 = backoff.nextBackoff(Duration.ofSeconds(1));
    // start backoff of 2s
    assertThat(r1).isEqualTo(BackoffDuration.of(Duration.ofSeconds(2)));
    // higher level failures happens only 300ms into our 2s
    backoff.backoffInterrupted(Duration.ofMillis(300));

    // record higher level failure
    BackoffResult r2 = backoff.nextBackoff(Duration.ofMillis(300));
    // backoff 4s (previous was 2s w/ 2.0 multiplier = 4s)
    // even though the previous backoff duration wasn't fully consumed, still use it as the basis
    // for the next backoff
    assertThat(r2).isEqualTo(BackoffDuration.of(Duration.ofSeconds(4)));
    // another failure 3s after the 4s backoff finished
    BackoffResult r3 = backoff.nextBackoff(Duration.ofSeconds(3));
    assertThat(r3).isEqualTo(BackoffDuration.of(Duration.ofSeconds(8)));
    // another failure 5s after the 8s backoff finished
    BackoffResult r4 = backoff.nextBackoff(Duration.ofSeconds(5));
    // 11s backoff because 11s is maxBackoff
    assertThat(r4).isEqualTo(BackoffDuration.of(Duration.ofSeconds(11)));
    // another failure 7s after the 11s backoff finished
    BackoffResult r5 = backoff.nextBackoff(Duration.ofSeconds(7));
    // at this point it has been ~39s, which is more than our timeout of 34s
    assertThat(r5).isEqualTo(BackoffResults.EXHAUSTED);
  }

  @Test
  public void simple() {
    Backoff backoff = defaultBackoff();

    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(2)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(4)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(8)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(16)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(32)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(57)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(57)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(57)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(57)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(57)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(57)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffResults.EXHAUSTED);
  }

  @Test
  public void happyPath() {
    Backoff backoff = defaultBackoff();

    Duration elapsed = Duration.ofMinutes(6).plusSeconds(58);
    assertThat(backoff.nextBackoff(elapsed)).isEqualTo(BackoffResults.EXHAUSTED);
  }

  @Test
  public void elapsedDurationProvidedToNextBackoffMustBeGtEqZero() {
    Backoff backoff = defaultBackoff();

    Duration elapsed = Duration.ofSeconds(-1);
    IllegalArgumentException iae =
        assertThrows(IllegalArgumentException.class, () -> backoff.nextBackoff(elapsed));

    assertThat(iae).hasMessageThat().isEqualTo("elapsed must be >= PT0S (PT-1S >= PT0S)");
  }

  @Test
  public void resetWorks() {
    Backoff backoff =
        Backoff.newBuilder()
            .setInitialBackoff(Duration.ofSeconds(2))
            .setMaxBackoff(Duration.ofSeconds(5))
            .setTimeout(Duration.ofSeconds(6))
            .setJitterer(Jitterer.noJitter())
            .setRetryDelayMultiplier(2.0)
            .build();

    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(2)));
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffResults.EXHAUSTED);
    backoff.reset();
    assertThat(backoff.nextBackoff(Duration.ofSeconds(10))).isEqualTo(BackoffResults.EXHAUSTED);
  }

  @Test
  public void onceExhaustedStaysExhaustedUntilReset() {
    Backoff backoff =
        Backoff.newBuilder()
            .setInitialBackoff(Duration.ofSeconds(2))
            .setMaxBackoff(Duration.ofSeconds(5))
            .setTimeout(Duration.ofSeconds(5))
            .setJitterer(Jitterer.noJitter())
            .setRetryDelayMultiplier(1.0)
            .build();

    assertThat(backoff.nextBackoff(Duration.ofSeconds(5))).isEqualTo(BackoffResults.EXHAUSTED);
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffResults.EXHAUSTED);
    backoff.reset();
    assertThat(backoff.nextBackoff(ZERO)).isEqualTo(BackoffDuration.of(Duration.ofSeconds(2)));
  }

  @Test
  public void ifANextBackoffWouldExceedTheTimeoutItShouldBeConsideredExhausted() {
    Backoff backoff =
        Backoff.newBuilder()
            .setInitialBackoff(Duration.ofSeconds(2))
            .setMaxBackoff(Duration.ofSeconds(6))
            .setTimeout(Duration.ofSeconds(24))
            .setJitterer(Jitterer.noJitter())
            .setRetryDelayMultiplier(2.0)
            .build();

    assertThat(backoff.nextBackoff(Duration.ofSeconds(5)))
        .isEqualTo(BackoffDuration.of(Duration.ofSeconds(2)));

    assertThat(backoff.nextBackoff(Duration.ofSeconds(15))).isEqualTo(BackoffResults.EXHAUSTED);
  }

  @Test
  public void noJitter_alwaysReturnsInput() {
    Jitterer jitterer = Jitterer.noJitter();
    Duration _5s = Duration.ofSeconds(5);
    Duration _10s = Duration.ofSeconds(10);
    Duration _30s = Duration.ofSeconds(30);
    assertThat(jitterer.jitter(_5s)).isEqualTo(_5s);
    assertThat(jitterer.jitter(_10s)).isEqualTo(_10s);
    assertThat(jitterer.jitter(_30s)).isEqualTo(_30s);
  }

  @Test
  public void threadLocalRandomJitter_works() {
    Jitterer jitterer = Jitterer.threadLocalRandom();
    Duration min = Duration.ofNanos(-1);
    Duration _5s = Duration.ofSeconds(5);
    Duration _10s = Duration.ofSeconds(10);
    Duration _30s = Duration.ofSeconds(30);
    assertThat(jitterer.jitter(_5s)).isGreaterThan(min);
    assertThat(jitterer.jitter(_10s)).isGreaterThan(min);
    assertThat(jitterer.jitter(_30s)).isGreaterThan(min);

    assertThat(jitterer.jitter(min)).isEqualTo(min);
  }

  private static Backoff defaultBackoff() {
    return Backoff.newBuilder()
        .setInitialBackoff(Duration.ofSeconds(2))
        .setMaxBackoff(Duration.ofSeconds(57))
        .setTimeout(Duration.ofMinutes(7))
        .setJitterer(Jitterer.noJitter())
        .setRetryDelayMultiplier(2.0)
        .build();
  }
}
