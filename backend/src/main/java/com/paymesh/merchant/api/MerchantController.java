package com.paymesh.merchant.api;

import com.paymesh.merchant.application.ChangeMerchantStatusService;
import com.paymesh.merchant.application.IssueApiCredentialService;
import com.paymesh.merchant.application.GetMerchantService;
import com.paymesh.merchant.application.MerchantNotFoundException;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.KycSubmissionNotFoundException;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.merchant.application.ReviewKycSubmissionService;
import com.paymesh.merchant.application.UpdateMerchantService;
import com.paymesh.merchant.domain.ApiCredentialId;
import com.paymesh.merchant.domain.KycSubmissionId;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.security.CallerRole;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;


@RestController
@RequestMapping("api/v1/merchants")
public final class MerchantController {
    private final RegisterMerchantService registerMerchantService;

    private final GetMerchantService getMerchantService;

    private final UpdateMerchantService updateMerchantService;

    private final ChangeMerchantStatusService changeMerchantStatusService;

    private final ReviewKycSubmissionService reviewKycSubmissionService;

    private final IssueApiCredentialService issueApiCredentialService;

    public MerchantController(
        RegisterMerchantService registerMerchantService,
        GetMerchantService getMerchantService,
        UpdateMerchantService updateMerchantService,
        ChangeMerchantStatusService changeMerchantStatusService,
        ReviewKycSubmissionService reviewKycSubmissionService,
        IssueApiCredentialService issueApiCredentialService
    ) {
        this.registerMerchantService = registerMerchantService;
        this.getMerchantService = getMerchantService;
        this.updateMerchantService = updateMerchantService;
        this.changeMerchantStatusService = changeMerchantStatusService;
        this.reviewKycSubmissionService = reviewKycSubmissionService;
        this.issueApiCredentialService = issueApiCredentialService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MerchantResponse register(@Valid @RequestBody RegisterMerchantRequest request) {
        RegisterMerchantCommand command = new RegisterMerchantCommand(
            request.businessName(),
            request.email(),
            request.country(),
            request.defaultCurrency()
        );

        Merchant merchant = registerMerchantService.register(command);

        return MerchantResponse.from(merchant);
    }

    /**
     * A merchant may only be read by someone who holds a role at it.
     * <p>
     * A caller asking for someone else's merchant gets the same 404 as one asking for a merchant
     * that was never created. Answering 403 would confirm the id exists, turning this endpoint into
     * an oracle for enumerating tenants.
     */
    @GetMapping("/{merchantId}")
    MerchantResponse getById(@PathVariable String merchantId, AuthenticatedCaller caller) {
        MerchantId requested = MerchantId.from(merchantId);

        if (!caller.canActFor(requested)) {
            throw new MerchantNotFoundException(requested);
        }

        return MerchantResponse.from(getMerchantService.getById(requested));
    }

    /**
     * SDD 9.3's profile update, and MERCHANT_ADMIN only.
     * <p>
     * A MERCHANT_USER running day-to-day operations has no business renaming the company, and this
     * is the cheapest possible demonstration that the role is now read rather than discarded.
     */
    @PatchMapping("/{merchantId}")
    MerchantResponse update(
        @PathVariable String merchantId,
        @Valid @RequestBody UpdateMerchantRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId requested = requireOwn(merchantId, caller, CallerRole.MERCHANT_ADMIN);

        return MerchantResponse.from(
            updateMerchantService.rename(requested, request.businessName())
        );
    }

    // --- platform administration ------------------------------------------------------------

    /**
     * PLATFORM STAFF ONLY. Activating, suspending and closing a merchant are acts performed ON a
     * tenant by someone outside it -- a merchant able to lift its own suspension would make
     * suspension advisory (ADR-021).
     * <p>
     * Note these routes are exempt from {@code MerchantStatusFilter}: they necessarily act on a
     * merchant that is not ACTIVE, and guarding them would make suspension irreversible.
     */
    @PostMapping("/{merchantId}/activate")
    MerchantResponse activate(
        @PathVariable String merchantId,
        @Valid @RequestBody(required = false) ChangeMerchantStatusRequest request,
        AuthenticatedCaller caller
    ) {
        return MerchantResponse.from(changeMerchantStatusService.activate(
            MerchantId.from(merchantId), caller.requirePlatformAdmin(), reasonOf(request)
        ));
    }

    @PostMapping("/{merchantId}/suspend")
    MerchantResponse suspend(
        @PathVariable String merchantId,
        @Valid @RequestBody ChangeMerchantStatusRequest request,
        AuthenticatedCaller caller
    ) {
        return MerchantResponse.from(changeMerchantStatusService.suspend(
            MerchantId.from(merchantId), caller.requirePlatformAdmin(), reasonOf(request)
        ));
    }

    @PostMapping("/{merchantId}/close")
    MerchantResponse close(
        @PathVariable String merchantId,
        @Valid @RequestBody ChangeMerchantStatusRequest request,
        AuthenticatedCaller caller
    ) {
        return MerchantResponse.from(changeMerchantStatusService.close(
            MerchantId.from(merchantId), caller.requirePlatformAdmin(), reasonOf(request)
        ));
    }

    // --- KYC ----------------------------------------------------------------------------------

    /**
     * THE ONE WRITE AN UNVERIFIED MERCHANT MAY MAKE.
     * <p>
     * {@code MerchantStatusFilter} exempts this path deliberately: refusing it would make
     * verification unreachable, which is the deadlock rather than the fix. MERCHANT_ADMIN, because
     * declaring a company's legal identity is not a day-to-day operational act.
     */
    @PostMapping("/{merchantId}/kyc-submissions")
    @ResponseStatus(HttpStatus.CREATED)
    KycSubmissionResponse submitKyc(
        @PathVariable String merchantId,
        @Valid @RequestBody SubmitKycRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId requested = requireOwn(merchantId, caller, CallerRole.MERCHANT_ADMIN);

        return KycSubmissionResponse.from(reviewKycSubmissionService.submit(
            requested, request.legalName(), request.registrationId()
        ));
    }

    @GetMapping("/{merchantId}/kyc-submissions")
    List<KycSubmissionResponse> listKyc(
        @PathVariable String merchantId,
        AuthenticatedCaller caller
    ) {
        MerchantId requested = requireOwn(merchantId, caller, CallerRole.MERCHANT_ADMIN);

        return reviewKycSubmissionService.list(requested).stream()
            .map(KycSubmissionResponse::from)
            .toList();
    }

    /**
     * PLATFORM STAFF ONLY, and APPROVAL IS WHAT ACTIVATES THE MERCHANT -- in the same transaction,
     * so a green submission can never sit next to a frozen merchant.
     */
    @PostMapping("/kyc-submissions/{kycSubmissionId}/approve")
    KycSubmissionResponse approveKyc(
        @PathVariable String kycSubmissionId,
        @Valid @RequestBody(required = false) ReviewKycRequest request,
        AuthenticatedCaller caller
    ) {
        return KycSubmissionResponse.from(reviewKycSubmissionService.approve(
            KycSubmissionId.from(kycSubmissionId),
            caller.requirePlatformAdmin(),
            request == null ? null : request.notes()
        ));
    }

    /** Leaves the merchant exactly where it was: a typo must not be terminal. */
    @PostMapping("/kyc-submissions/{kycSubmissionId}/reject")
    KycSubmissionResponse rejectKyc(
        @PathVariable String kycSubmissionId,
        @Valid @RequestBody(required = false) ReviewKycRequest request,
        AuthenticatedCaller caller
    ) {
        return KycSubmissionResponse.from(reviewKycSubmissionService.reject(
            KycSubmissionId.from(kycSubmissionId),
            caller.requirePlatformAdmin(),
            request == null ? null : request.notes()
        ));
    }

    // --- API credentials ------------------------------------------------------------------

    /**
     * SDD 9.3. THE SECRET IS IN THIS RESPONSE AND NOWHERE ELSE, EVER.
     *
     * <h2>MERCHANT_ADMIN ONLY, AND A KEY CANNOT MINT A KEY</h2>
     *
     * Issuing a credential widens access, so it needs the administrative role. It also means a
     * leaked MERCHANT_USER key cannot escalate itself -- and a leaked MERCHANT_ADMIN key can only
     * mint keys no more powerful than itself, because {@code ck_api_credentials_role} refuses
     * PLATFORM_ADMIN outright.
     */
    @PostMapping("/{merchantId}/api-credentials")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedApiCredentialResponse createCredential(
        @PathVariable String merchantId,
        @Valid @RequestBody CreateApiCredentialRequest request,
        AuthenticatedCaller caller
    ) {
        MerchantId requested = requireOwn(merchantId, caller, CallerRole.MERCHANT_ADMIN);

        return CreatedApiCredentialResponse.from(issueApiCredentialService.issue(
            requested,
            CallerRole.parse(request.role()).orElseThrow(
                () -> new IllegalArgumentException("Unknown role " + request.role())
            ),
            request.label()
        ));
    }

    /** Never returns a secret -- {@code ApiCredentialResponse} has no field for one. */
    @GetMapping("/{merchantId}/api-credentials")
    List<ApiCredentialResponse> listCredentials(
        @PathVariable String merchantId,
        AuthenticatedCaller caller
    ) {
        MerchantId requested = requireOwn(merchantId, caller, CallerRole.MERCHANT_ADMIN);

        return issueApiCredentialService.list(requested).stream()
            .map(ApiCredentialResponse::from)
            .toList();
    }

    /** Revocation is a state change, not a delete -- see {@code ApiCredential}. */
    @DeleteMapping("/{merchantId}/api-credentials/{apiCredentialId}")
    ApiCredentialResponse revokeCredential(
        @PathVariable String merchantId,
        @PathVariable String apiCredentialId,
        AuthenticatedCaller caller
    ) {
        MerchantId requested = requireOwn(merchantId, caller, CallerRole.MERCHANT_ADMIN);

        return ApiCredentialResponse.from(issueApiCredentialService.revoke(
            requested, ApiCredentialId.from(apiCredentialId)
        ));
    }

    /**
     * The merchant in the path must be the caller's own, and they must hold {@code required} there.
     * <p>
     * Another merchant's id is a 404, not a 403 -- same rule as the read above, and for the same
     * reason: 403 would confirm the tenant exists.
     */
    private static MerchantId requireOwn(
        String merchantId,
        AuthenticatedCaller caller,
        CallerRole required
    ) {
        MerchantId requested = MerchantId.from(merchantId);

        if (!caller.canActFor(requested)) {
            throw new MerchantNotFoundException(requested);
        }

        MerchantId scoped = caller.requireSingleMerchantWith(required);

        if (!scoped.equals(requested)) {
            throw new MerchantNotFoundException(requested);
        }

        return requested;
    }

    private static String reasonOf(ChangeMerchantStatusRequest request) {
        return request == null ? null : request.reason();
    }
}
