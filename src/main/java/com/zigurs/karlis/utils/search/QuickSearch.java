/*
 *                                     //
 * Copyright 2016 Karlis Zigurs (http://zigurs.com)
 *                                   //
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.zigurs.karlis.utils.search;

import com.zigurs.karlis.utils.search.fj.FJIntersectionTask;
import com.zigurs.karlis.utils.search.fj.FJUnionTask;
import com.zigurs.karlis.utils.search.graph.QSGraph;
import com.zigurs.karlis.utils.search.model.Item;
import com.zigurs.karlis.utils.search.model.Result;
import com.zigurs.karlis.utils.search.model.Stats;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.zigurs.karlis.utils.search.QuickSearch.AccumulationPolicy.UNION;
import static com.zigurs.karlis.utils.search.QuickSearch.UnmatchedPolicy.BACKTRACKING;
import static com.zigurs.karlis.utils.sort.MagicSort.sortAndLimit;

/**
 * Simple and lightweight in-memory quick search provider.
 * <p>
 * Fit for low latency querying of small to medium sized datasets (limited by memory) to enable users
 * immediately see the top hits for their partially entered search string. Based on production experience
 * this approach is well perceived by users and their ability to see the top hits immediately allows
 * them to adjust their queries on the fly getting to the desired result faster.
 * <p>
 * By implementing this functionality directly in the app or corresponding backend the overall complexity of the
 * project can be significantly reduced - there is no need to care about maintaining search infrastructure, servers,
 * software or APIs.
 * <p>
 * Example uses can include:
 * <ul>
 * <li>Selecting from a list of existing contacts</li>
 * <li>Looking for a particular city (associating it with known aliases, landmarks, state, etc)</li>
 * <li>Searching for an item in an online (book) shop</li>
 * <li>Used in background to highlight items that match the (partial) keywords entered. A.la. OSX System Preferences search</li>
 * <li>Navigating large navigation trees, in example all sporting events for a year</li>
 * </ul>
 * <p>
 * Typical use case would be including it in ether application or a web server, maintaining the
 * data set (ether via provided add and remove methods or by clearing and repopulating the search contents
 * completely) and exposing an API to user that accepts a free-form input and returns corresponding matching items.
 * <p>
 * Each entry is associated with a number of keywords that are not exposed to user, therefore it is possible to add
 * name aliases or item class descriptions to keywords. Same applies to letting users discover items by unique identifiers
 * or alternate spellings.
 * <p>
 * An example contacts list is provided as example (entry followed by assigned keywords):
 * <table summary="">
 * <tr><th>Item</th><th>Supplied keywords</th></tr>
 * <tr><td>"Jane Doe, 1234"</td><td>"Jane Doe Marketing Manager SEO Community MySpace 1234"</td></tr>
 * <tr><td>"Alice Stuggard, 9473"</td><td>"Alice Stuggard Tech Cryptography Manager RSA 9473"</td></tr>
 * <tr><td>"Robert Howard, 6866"</td><td>"Robert Bob Howard Tech Necromancy Summoning Undead Cryptography BOFH RSA DOD Laundry 6866"</td></tr>
 * <tr><td>"Eve Moneypenny, 9223"</td><td>"Eve Moneypenny Accounting Manager Q OSA 9223"</td></tr>
 * </table>
 * <p>
 * In the example above if the user enters <code><strong>"Mana"</strong></code> he will be served a list of Jane,
 * Alice and Eve as their keyword <code><strong>"Manager"</strong></code> is matched by
 * <code><strong>"Mana"</strong></code>. Now user should see that the result set is sufficiently narrow and
 * can tailor his search further by continuing on to type <code><strong>"Mana a"</strong></code> - which will lead
 * to Alice and Eve being promoted to top of results. Alice because of her name match and Eve because of her department.
 * <code><strong>"Mana acc"</strong></code> will narrow the results to Eve only as she is only one in the search set
 * that can match both <code><strong>*mana*</strong></code> and <code><strong>*acc*</strong></code>.
 * <p>
 * Example use:
 * <p>
 * <code>QuickSearch&lt;String&gt; qs = new QuickSearch&lt;&gt;();<br>
 * qs.addItem("Villain", "Roy Batty Lord Voldemort Colonel Kurtz");<br>
 * qs.addItem("Hero", "Walt Kowalksi Jake Blues Shaun");<br>
 * System.out.println(qs.findItem("walk")); // finds "Hero"</code>
 * <p>
 * Concurrency - This class is thread safe. Implementation is completely passive
 * and can be deployed horizontally as identical datasets will produce identical search results.
 *
 * @author Karlis Zigurs, 2016
 */
public class QuickSearch<T> {

    /**
     * Matching policy to apply to unmatched keywords. In case of EXACT only
     * exact supplied keyword matches will be considered, in case of BACKTRACKING
     * any keywords with no matches will be incrementally shortened until first
     * candidate match is found (e.g. supplied 'terminal' will be shortened until it
     * reaches 'ter' where it can match against 'terra').
     */
    public enum UnmatchedPolicy {
        EXACT, BACKTRACKING
    }

    /**
     * If multiple keywords are supplied select strategy to accumulate result set.
     * <p>
     * UNION will consider all items found for each keyword in the result,
     * INTERSECTION will consider only items that are matched by all the supplied
     * keywords.
     * <p>
     * INTERSECTION is significantly more performant as it discards
     * candidates as early as possible.
     */
    public enum AccumulationPolicy {
        UNION, INTERSECTION
    }

    /**
     * Function to 'clean up' supplied keyword and user input strings. We assume that the input is
     * going to be ether free form or malformed, therefore this allows to apply required actions to generate
     * a 'clean' set of keywords from the input string.
     * <p>
     * In example both "one two,three-four" and "one$two%three^four" as inputs will produce
     * set of 4 strings [one,two,three,four] on the output.
     */
    public static final Function<String, Set<String>> DEFAULT_KEYWORDS_EXTRACTOR =
            s -> Arrays.stream(s.replaceAll("[^\\w]+", " ").split("[\\s]+")).collect(Collectors.toSet());

    /**
     * Function to sanitize search keywords before using them internally. Applied to both keywords
     * supplied with items and to user input before performing search.
     * <p>
     * Rationale is to allow somewhat relaxed free-form text input (e.g. phone devices automatically capitalising
     * entered keywords) and extra capability to remap special characters to their latin alphabet equivalents.
     * <p>
     * The normalized representation has no specific requirements, this is just a convenience method.
     * Simply returning the supplied string will mean that the search results contain only exact (and case
     * sensitive) matches. It is also possible to return empty strings here, in which case the supplied
     * keyword will be ignored.
     * <p>
     * Example transformations:
     * <table summary="">
     * <tr><th>Original</th><th>Transformed</th><th>Reason</th></tr>
     * <tr><td><code>"New York"</code></td><td><code>"new york"</code></td><td>remove upper case</td></tr>
     * <tr><td><code>"Pythøn"</code></td><td><code>"python"</code></td><td>replace special characters</td></tr>
     * <tr><td><code>"HERMSGERVØRDENBRØTBØRDA"</code></td><td><code>"hermsgervordenbrotborda"</code></td><td>it could happen...</td></tr>
     * <tr><td><code>"Россия"</code></td><td><code>"rossiya"</code></td><td>translate cyrilic alphabet to latin</td></tr>
     * </table>
     * <p>
     * Default implementation assumes that String.trim().toLowerCase() is sufficient.
     */
    public static final Function<String, String> DEFAULT_KEYWORD_NORMALIZER = s -> s.trim().toLowerCase();

    /**
     * Function scoring user supplied input against corresponding keywords associated with search items.
     * <p>
     * An example invocations might request to compare <code><strong>"swe"</strong></code> against
     * <code><strong>"sweater"</strong></code> or <code><strong>"count"</strong></code> against
     * <code><strong>"accounting"</strong></code>.
     * <p>
     * Default implementation returns the ratio between search term and keyword lengths with additional boost
     * if the search term matches beginning of the keyword.
     * <p>
     * In example, while matching user input against known keyword "password", the following will be calculated:
     * <ul>
     * <li>Input "pa" -&gt; low match (0.25), but boosted (+1) due to matching start of the keyword.</li>
     * <li>Input "swo" -&gt; low match (0.37), not boosted</li>
     * <li>Input "assword" -&gt; high match (0.87), not boosted</li>
     * <li>Input "password" -&gt; high match (1), also boosted by matching the beginning of the line (+1)</li>
     * </ul>
     * <p>
     * All keywords supplied by user are scored against all matching keywords associated with a searchable item.
     * Items rank in the results is determined by the sum of all score results.
     */
    public static final BiFunction<String, String, Double> DEFAULT_MATCH_SCORER = (keywordMatch, keyword) -> {
        /* 0...1 depending on the length ratio */
        double matchScore = (double) keywordMatch.length() / (double) keyword.length();

        /* boost by 1 if matches start of keyword */
        if (keyword.startsWith(keywordMatch))
            matchScore += 1.0;

        return matchScore;
    };

    /*
     * Instance properties
     */

    private final AccumulationPolicy accumulationPolicy;
    private final UnmatchedPolicy unmatchedPolicy;

    private final BiFunction<String, String, Double> keywordMatchScorer;
    private final Function<String, String> keywordNormalizer;
    private final Function<String, Set<String>> keywordsExtractor;

    private final QSGraph<T> graph;

    private final boolean enableForkJoin;

    /**
     * Private constructor, use builder instead.
     *
     * @param builder supplies configuration
     */
    private QuickSearch(final QuickSearchBuilder builder) {
        keywordsExtractor = builder.keywordsExtractor;
        keywordNormalizer = builder.keywordNormalizer;
        keywordMatchScorer = builder.keywordMatchScorer;
        unmatchedPolicy = builder.unmatchedPolicy;
        accumulationPolicy = builder.accumulationPolicy;
        enableForkJoin = builder.enableForkJoin;

        graph = new QSGraph<>();
    }

    /**
     * QuickSearchBuilder instance to configure search object.
     *
     * @return builder instance
     */
    public static QuickSearchBuilder builder() {
        return new QuickSearchBuilder();
    }

    /*
     * Public interface
     */

    /**
     * Add an item with corresponding keywords, e.g. an online store item Shoe with
     * keywords <code><strong>"Shoe Red 10 Converse cheap free"</strong></code>.
     * <p>
     * You can expand the keywords stored against an item by adding it again with extra keywords.
     * If the item is already in the database any new keywords will be mapped to it.
     *
     * @param item     Item to return for search results
     * @param keywords Arbitrary list of keywords separated by space, comma, special characters, freeform text...
     * @return True if the item was added, false if no keywords to map against the item were found (therefore item was not added)
     */
    public boolean addItem(final T item, final String keywords) {
        if (item == null || keywords == null || keywords.isEmpty())
            return false;

        ImmutableSet<String> keywordsSet = prepareKeywords(keywords, keywordsExtractor, keywordNormalizer, true);

        if (keywordsSet.isEmpty())
            return false;

        addItemImpl(item, keywordsSet);

        return true;
    }

    /**
     * Remove an item, if it exists. Calling this method ensures that specified item
     * and any keywords it was associated with is gone.
     * <p>
     * Or it does nothing if no such item was present.
     *
     * @param item Item to remove
     */
    public void removeItem(final T item) {
        if (item == null)
            return;

        removeItemImpl(item);
    }

    /**
     * Find top matching item for the supplied search string
     *
     * @param searchString Raw search string
     * @return Optional containing (or not) the top scoring item
     */
    public Optional<T> findItem(final String searchString) {
        if (isInvalidRequest(searchString, 1))
            return Optional.empty();

        ImmutableSet<String> searchKeywords = prepareKeywords(searchString, keywordsExtractor, keywordNormalizer, false);

        if (searchKeywords.isEmpty())
            return Optional.empty();

        List<SearchResult<T>> results = doSearch(searchKeywords, 1);

        if (results.isEmpty())
            return Optional.empty();
        else
            return Optional.of(results.get(0).unwrap());
    }

    /**
     * Find top n items matching the supplied search string. Supplied string will be processed by
     * keyword extracting and normalizing functions before used for search.
     *
     * @param searchString     Raw search string, e.g. "new york pizza"
     * @param numberOfTopItems Number of items the returned result should be limited to
     * @return List of 0 to numberOfTopItems elements
     */
    public List<T> findItems(final String searchString, final int numberOfTopItems) {
        if (isInvalidRequest(searchString, numberOfTopItems))
            return Collections.emptyList();

        ImmutableSet<String> searchKeywords = prepareKeywords(searchString, keywordsExtractor, keywordNormalizer, false);

        if (searchKeywords.isEmpty())
            return Collections.emptyList();

        List<SearchResult<T>> results = doSearch(searchKeywords, numberOfTopItems);

        if (results.isEmpty()) {
            return Collections.emptyList();
        } else {
            return results.stream()
                    .map(SearchResult::unwrap)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Find top matching item for the supplied search string and return it
     * wrapped in the augumented response object.
     *
     * @param searchString Raw search string
     * @return Possibly empty Optional wrapping item, keywords and score
     */
    public Optional<Item<T>> findItemWithDetail(final String searchString) {
        if (isInvalidRequest(searchString, 1))
            return Optional.empty();

        ImmutableSet<String> searchKeywords = prepareKeywords(searchString, keywordsExtractor, keywordNormalizer, false);

        if (searchKeywords.isEmpty())
            return Optional.empty();

        List<SearchResult<T>> results = doSearch(searchKeywords, 1);

        if (results.isEmpty()) {
            return Optional.empty();
        } else {
            SearchResult<T> w = results.get(0);
            return Optional.of(
                    new Item<>(
                            w.unwrap(),
                            graph.getItemKeywords(w.unwrap()),
                            w.getScore()
                    )
            );
        }
    }

    /**
     * Request an augumented result containing the search string, scores for all items
     * and list of keywords matched (can be used to provide hints to user).
     *
     * @param searchString     Raw search string, e.g. "new york pizza"
     * @param numberOfTopItems Number of items the result should be limited to
     * @return Result object containing 0 to n top scoring items and corresponding metadata
     */
    public Result<T> findItemsWithDetail(final String searchString, final int numberOfTopItems) {
        if (isInvalidRequest(searchString, numberOfTopItems))
            return new Result<>(searchString != null ? searchString : "", Collections.emptyList());

        ImmutableSet<String> searchKeywords = prepareKeywords(searchString, keywordsExtractor, keywordNormalizer, false);

        if (searchKeywords.isEmpty())
            return new Result<>(searchString, Collections.emptyList());

        List<SearchResult<T>> results = doSearch(searchKeywords, numberOfTopItems);

        if (results.isEmpty()) {
            return new Result<>(searchString, Collections.emptyList());
        } else {
            return new Result<>(
                    searchString,
                    results.stream()
                            .map(i -> new Item<>(
                                    i.unwrap(),
                                    graph.getItemKeywords(i.unwrap()),
                                    i.getScore())
                            )
                            .collect(Collectors.toList())
            );
        }
    }

    /**
     * Clear the search database.
     */
    public void clear() {
        graph.clear();
    }

    /**
     * Returns an overview of contained maps sizes.
     *
     * @return stats listing number of items, keywords and fragments known
     */
    public Stats getStats() {
        return graph.getStats();
    }

    /*
     * Implementation methods
     */

    private boolean isInvalidRequest(final String searchString, final int numItems) {
        return searchString == null || searchString.isEmpty() || numItems < 1;
    }

    private List<SearchResult<T>> doSearch(final ImmutableSet<String> searchKeywords,
                                           final int maxItemsToList) {
        return sortAndLimit(
                findAndScore(searchKeywords).entrySet(),
                maxItemsToList,
                (e1, e2) -> e2.getValue().compareTo(e1.getValue())
        ).stream()
                .map(e -> new SearchResult<>(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private Map<T, Double> findAndScore(final ImmutableSet<String> suppliedFragments) {
        /* Avoid calling into merges if we are looking for only one keyword */
        if (suppliedFragments.size() == 1)
            return walkGraphAndScore(suppliedFragments.getSingleElement());

        /* Merges will be inevitable */
        if (accumulationPolicy == UNION)
            return findAndScoreUnion(suppliedFragments);
        else // implied (withAccumulationPolicy == INTERSECTION)
            return findAndScoreIntersection(suppliedFragments);
    }

    private Map<T, Double> findAndScoreUnion(final ImmutableSet<String> suppliedFragments) {
        if (enableForkJoin) {
            return new FJUnionTask<>(suppliedFragments, this::walkGraphAndScore).fork().join();

        } else {
            final Map<T, Double> accumulatedItems = new HashMap<>();

            suppliedFragments.forEach(fragment ->
                    walkGraphAndScore(fragment).forEach((k, v) ->
                            accumulatedItems.merge(k, v, (d1, d2) -> d1 + d2))
            );

            return accumulatedItems;
        }
    }

    private Map<T, Double> findAndScoreIntersection(final ImmutableSet<String> suppliedFragments) {
        if (enableForkJoin) {
            return new FJIntersectionTask<>(suppliedFragments, this::walkGraphAndScore).fork().join();

        } else {
            Map<T, Double> accumulatedItems = null;

            for (String suppliedFragment : suppliedFragments) {
                Map<T, Double> fragmentItems = walkGraphAndScore(suppliedFragment);

                if (fragmentItems.isEmpty()) // results will be empty too, return early
                    return fragmentItems;

                if (accumulatedItems == null) {
                    accumulatedItems = fragmentItems;
                } else {
                    accumulatedItems = intersectMaps(fragmentItems, accumulatedItems);

                    if (accumulatedItems.isEmpty())
                        return accumulatedItems;
                }
            }

            return accumulatedItems;
        }
    }

    /*
     * Interfacing with the graph
     */

    private void addItemImpl(final T item, final Set<String> suppliedKeywords) {
        graph.registerItem(item, suppliedKeywords);
    }

    private void removeItemImpl(final T item) {
        graph.unregisterItem(item);
    }

    private Map<T, Double> walkGraphAndScore(final String keyword) {
        Map<T, Double> result = graph.walkAndScore(keyword, keywordMatchScorer);

        /* Check if we need to back off */
        if (unmatchedPolicy == BACKTRACKING
                && result.isEmpty()
                && keyword.length() > 1)
            return walkGraphAndScore(keyword.substring(0, keyword.length() - 1));

        return result;
    }

    private static ImmutableSet<String> prepareKeywords(final String keywordsString,
                                                        final Function<String, Set<String>> extractorFunction,
                                                        final Function<String, String> normalizerFunction,
                                                        final boolean internKeywords) {
        return ImmutableSet.fromCollection(
                extractorFunction.apply(keywordsString).stream()
                        .filter(s -> s != null)
                        .map(normalizerFunction)
                        .filter(s -> !s.isEmpty())
                        .map(s -> internKeywords ? s.intern() : s)
                        .collect(Collectors.toSet()) // implies distinct
        );
    }

    /*
     * Helpers
     */

    /**
     * Returns intersection of two provided maps (or empty map if no
     * keys overlap) summing the values.
     * <p>
     * For performance reasons this function _modifies maps supplied_  and possibly
     * returns an instance of one of the supplied (by then modified) maps.
     *
     * @param left  map to intersect
     * @param right map to intersect
     * @param <T>   type of keys
     * @return intersection with values summed
     */
    public static <T> Map<T, Double> intersectMaps(final Map<T, Double> left,
                                                   final Map<T, Double> right) {
        Map<T, Double> smaller = left.size() < right.size() ? left : right;
        Map<T, Double> bigger = smaller == left ? right : left;

        smaller.keySet().retainAll(bigger.keySet());
        smaller.entrySet().forEach(e -> e.setValue(e.getValue() + bigger.get(e.getKey())));
        return smaller;
    }

    /*
     * Builder.
     */

    /**
     * {@link QuickSearch} builder class.
     * <p>
     * Access and use: {@code QuickSearch.builder().build();}
     */
    public static class QuickSearchBuilder {

        private BiFunction<String, String, Double> keywordMatchScorer = DEFAULT_MATCH_SCORER;
        private Function<String, String> keywordNormalizer = DEFAULT_KEYWORD_NORMALIZER;
        private Function<String, Set<String>> keywordsExtractor = DEFAULT_KEYWORDS_EXTRACTOR;
        private UnmatchedPolicy unmatchedPolicy = BACKTRACKING;
        private AccumulationPolicy accumulationPolicy = UNION;
        private boolean enableForkJoin = false;

        public QuickSearchBuilder withKeywordMatchScorer(BiFunction<String, String, Double> scorer) {
            Objects.requireNonNull(scorer);

            keywordMatchScorer = scorer;
            return this;
        }

        public QuickSearchBuilder withKeywordNormalizer(Function<String, String> normalizer) {
            Objects.requireNonNull(normalizer);

            keywordNormalizer = normalizer;
            return this;
        }

        public QuickSearchBuilder withKeywordsExtractor(Function<String, Set<String>> extractor) {
            Objects.requireNonNull(extractor);

            keywordsExtractor = extractor;
            return this;
        }

        public QuickSearchBuilder withUnmatchedPolicy(UnmatchedPolicy policy) {
            Objects.requireNonNull(policy);

            unmatchedPolicy = policy;
            return this;
        }

        public QuickSearchBuilder withAccumulationPolicy(AccumulationPolicy policy) {
            Objects.requireNonNull(policy);

            accumulationPolicy = policy;
            return this;
        }

        public QuickSearchBuilder withForkJoinProcessing() {
            enableForkJoin = true;
            return this;
        }

        public <T> QuickSearch<T> build() {
            return new QuickSearch<>(this);
        }
    }

    /**
     * Internal wrapper of item and score for results list.
     * <p>
     * Not for external use.
     */
    private static class SearchResult<T> {

        private final T item;
        private final double score;

        private SearchResult(final T item, final double score) {
            this.item = item;
            this.score = score;
        }

        private T unwrap() {
            return item;
        }

        private Double getScore() {
            return score;
        }
    }
}