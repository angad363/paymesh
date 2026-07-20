package com.paymesh.merchant.api;

import com.paymesh.merchant.application.GetMerchantService;
import com.paymesh.merchant.application.RegisterMerchantCommand;
import com.paymesh.merchant.application.RegisterMerchantService;
import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantId;
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

    @GetMapping("/{merchantId}")
    MerchantResponse getById(@PathVariable String merchantId) {
        Merchant merchant = getMerchantService.getById(MerchantId.from(merchantId));
        return MerchantResponse.from(merchant);
    }
}
