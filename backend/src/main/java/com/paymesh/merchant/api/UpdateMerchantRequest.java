package com.paymesh.merchant.api;

import jakarta.validation.constraints.Size;

/**
 * SDD 9.3's {@code PATCH /v1/merchants/{id}}. Nothing was editable before this -- the platform had
 * no update endpoint of any kind, so a merchant could not correct its own business name.
 *
 * <h2>WHAT IS DELIBERATELY NOT EDITABLE</h2>
 *
 * <ul>
 *   <li><b>email</b> -- it is the unique login-adjacent identifier and changing it is an account
 *       recovery flow, not a profile edit.</li>
 *   <li><b>country</b> and <b>defaultCurrency</b> -- both are baked into money already recorded.
 *       A merchant whose ledger holds INR entries cannot become a EUR merchant by editing a field;
 *       that is a new merchant.</li>
 *   <li><b>status</b> -- never writable through a profile edit. It moves through the explicit
 *       lifecycle routes and only for platform staff.</li>
 * </ul>
 *
 * Which leaves the business name, and that is honest rather than lazy: it is the only field on this
 * aggregate that is presentational.
 *
 * @param businessName null means "leave it alone", which is what makes this a PATCH
 */
public record UpdateMerchantRequest(
    @Size(min = 2, max = 200, message = "Business name must be between 2 and 200 characters")
    String businessName
) {
}
