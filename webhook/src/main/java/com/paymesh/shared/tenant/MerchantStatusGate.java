package com.paymesh.shared.tenant;

/**
 * May this merchant perform authenticated writes?
 *
 * <h2>A PORT DECLARED IN {@code shared}, NOW ANSWERED FROM THE PROJECTION</h2>
 *
 * Every authenticated write in the platform needs this one question, so asking each capability to
 * declare its own {@code MerchantLookup} would mean four identical ports for one question and four
 * places to forget it. It lives here in {@code shared}.
 * <p>
 * Until ADR-039 the {@code merchant} module implemented it by reading its own {@code merchants}
 * table. That was the last thing making a consumer read the merchant's authoritative table. Now it
 * is answered from the event-fed {@code merchant_ref} projection ({@code MerchantRefStore}), so no
 * consumer depends on the merchant table -- the whole point of PR 4.
 *
 * <h2>Why a verdict rather than a status</h2>
 *
 * Returning {@code MerchantStatus} would put the merchant enum in {@code shared} and therefore in
 * every module's reach, and every caller would then have to know which values permit trading. One
 * module owns that rule; everyone else asks the question and gets {@link MerchantTransactability} --
 * the three outcomes a projection read can have, no merchant enum leaked.
 */
public interface MerchantStatusGate {

    /**
     * @return {@link MerchantTransactability#ALLOWED} only for a projected {@code ACTIVE} merchant;
     *     {@link MerchantTransactability#DENIED} for a projected non-active one (suspended, closed,
     *     unverified -- indistinguishable on purpose, so no tenant state leaks);
     *     {@link MerchantTransactability#UNKNOWN} when the merchant is absent from the projection,
     *     which is propagation lag and must be handled as retryable, not as a denial.
     */
    MerchantTransactability verdict(MerchantId merchantId);
}
