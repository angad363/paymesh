package com.paymesh.merchant.api;

import com.paymesh.merchant.application.GetMerchantService;
import com.paymesh.merchant.application.MerchantNotFoundException;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.shared.security.AuthenticatedCaller;
import com.paymesh.shared.tenant.MerchantId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/v1/merchants")
public final class MerchantController {
    private final RegisterMerchantService registerMerchantService;

    private final GetMerchantService getMerchantService;

    public MerchantController(
        RegisterMerchantService registerMerchantService,
        GetMerchantService getMerchantService
                              ) {
        this.registerMerchantService = registerMerchantService;
        this.getMerchantService = getMerchantService;
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
}
