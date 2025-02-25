/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.client.dns;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;

final class SearchDomainDnsResolver extends AbstractUnwrappable<DnsResolver> implements DnsResolver {

    private static final Logger logger = LoggerFactory.getLogger(SearchDomainDnsResolver.class);

    private final List<String> searchDomains;
    private final int ndots;
    private volatile boolean closed;

    SearchDomainDnsResolver(DnsResolver delegate, List<String> searchDomains, int ndots) {
        super(delegate);
        this.searchDomains = validateSearchDomain(searchDomains);
        this.ndots = ndots;
    }

    private static List<String> validateSearchDomain(List<String> searchDomains) {
        return searchDomains.stream()
                            .map(searchDomain -> {
                                // '.' search domain could be removed because the hostname itself is queried
                                // anyway.
                                if (Strings.isNullOrEmpty(searchDomain) || ".".equals(searchDomain)) {
                                    return null;
                                }
                                String normalized = searchDomain;
                                if (searchDomain.charAt(0) == '.') {
                                    // Remove the leading dot.
                                    normalized = searchDomain.substring(1);
                                }
                                if (normalized.charAt(normalized.length() - 1) != '.') {
                                    // Add a trailing dot.
                                    normalized += '.';
                                }
                                try {
                                    // Try to create a sample DnsQuestion to validate the search domain.
                                    DnsQuestionWithoutTrailingDot.of("localhost." + normalized,
                                                                     DnsRecordType.A);
                                    return normalized;
                                } catch (Exception ex) {
                                    logger.warn("Ignoring a malformed search domain: '{}'", searchDomain, ex);
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(toImmutableList());
    }

    @Override
    public CompletableFuture<List<DnsRecord>> resolve(DnsQuestionContext ctx, DnsQuestion question) {
        final SearchDomainQuestionContext searchDomainCtx =
                new SearchDomainQuestionContext(question, searchDomains, ndots);
        final DnsQuestion firstQuestion = searchDomainCtx.nextQuestion();
        assert firstQuestion != null;
        return resolve0(ctx, searchDomainCtx, firstQuestion);
    }

    private CompletableFuture<List<DnsRecord>> resolve0(DnsQuestionContext ctx,
                                                        SearchDomainQuestionContext searchDomainCtx,
                                                        DnsQuestion question) {
        if (closed) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(
                    new IllegalStateException("resolver is closed already"));
        }

        if (ctx.isCompleted()) {
            // Other DnsRecordType may be resolved already.
            return UnmodifiableFuture.completedFuture(ImmutableList.of());
        }

        return unwrap().resolve(ctx, question).handle((records, cause) -> {
            if (records != null) {
                return UnmodifiableFuture.completedFuture(records);
            } else {
                final DnsQuestion nextQuestion = searchDomainCtx.nextQuestion();
                if (nextQuestion != null) {
                    // Attempt to query the next search domain
                    return resolve0(ctx, searchDomainCtx, nextQuestion);
                } else {
                    return UnmodifiableFuture.<List<DnsRecord>>exceptionallyCompletedFuture(cause);
                }
            }
        }).thenCompose(Function.identity());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        unwrap().close();
    }

    @VisibleForTesting
    static final class SearchDomainQuestionContext {

        private final DnsQuestion original;
        private final String originalName;
        private final List<String> searchDomains;
        private final int numSearchDomains;
        private final boolean shouldStartWithHostname;
        private final boolean hasTrailingDot;
        private volatile int numAttemptsSoFar;

        SearchDomainQuestionContext(DnsQuestion original, List<String> searchDomains, int ndots) {
            this.original = original;
            this.searchDomains = searchDomains;
            numSearchDomains = searchDomains.size();
            originalName = original.name();
            hasTrailingDot = originalName.endsWith(".");
            shouldStartWithHostname = hasNDots(originalName, ndots) || hasTrailingDot || numSearchDomains == 0;
        }

        private static boolean hasNDots(String hostname, int ndots) {
            for (int idx = hostname.length() - 1, dots = 0; idx >= 0; idx--) {
                if (hostname.charAt(idx) == '.' && ++dots >= ndots) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        DnsQuestion nextQuestion() {
            final DnsQuestion dnsQuestion = nextQuestion0();
            if (dnsQuestion != null) {
                numAttemptsSoFar++;
            }
            return dnsQuestion;
        }

        @Nullable
        private DnsQuestion nextQuestion0() {
            final int numAttemptsSoFar = this.numAttemptsSoFar;

            final int searchDomainPos;
            if (shouldStartWithHostname) {
                searchDomainPos = numAttemptsSoFar - 1;
            } else {
                if (numAttemptsSoFar == numSearchDomains) {
                    // The last attempt uses the hostname itself.
                    searchDomainPos = -1;
                } else {
                    searchDomainPos = numAttemptsSoFar;
                }
            }

            if (searchDomainPos >= numSearchDomains) {
                // No more search domain to try.
                return null;
            }

            final String searchDomain;
            // -1 means the hostname itself.
            if (searchDomainPos == -1) {
                searchDomain = null;
            } else {
                searchDomain = searchDomains.get(searchDomainPos);
            }

            return newQuestion(searchDomain);
        }

        private DnsQuestion newQuestion(@Nullable String searchDomain) {
            searchDomain = firstNonNull(searchDomain, "");
            final String hostname;
            if (hasTrailingDot) {
                if (searchDomain.isEmpty()) {
                    return original;
                }
                hostname = originalName + searchDomain;
            } else {
                hostname = originalName + '.' + searchDomain;
            }
            // - As the search domain is validated already, DnsQuestionWithoutTrailingDot should not raise an
            //   exception.
            // - Use originalName to delete the cache value in RefreshingAddressResolver when the DnsQuestion
            //   is evicted from CachingDnsResolver.
            return DnsQuestionWithoutTrailingDot.of(originalName, hostname, original.type());
        }
    }
}
