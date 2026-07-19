package com.paymesh.merchant.application;

import com.paymesh.merchant.domain.Merchant;
import com.paymesh.merchant.domain.MerchantStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegisterMerchantServiceTest {

    @Test
    void registersAndSavesMerchant() {

        Instant registrationTime = Instant.parse("2026-07-18T10:15:30Z");

        Clock fixedClock = Clock.fixed(
            registrationTime,
            ZoneOffset.UTC
        );

        FakeMerchantRepository repository = new FakeMerchantRepository();

        RegisterMerchantService service = new RegisterMerchantService(
                repository,
                fixedClock
            );

        RegisterMerchantCommand command = new RegisterMerchantCommand(
                "FreshBrew Cafe",
                "Owner@FreshBrew.Example",
                "in",
                "inr"
            );

        Merchant registeredMerchant = service.register(command);

        assertEquals(
            MerchantStatus.PENDING_VERIFICATION,
            registeredMerchant.status()
        );

        assertEquals(
            registrationTime,
            registeredMerchant.createdAt()
        );

        assertEquals(
            "owner@freshbrew.example",
            registeredMerchant.email()
        );

        assertEquals(
            List.of(registeredMerchant),
            repository.savedMerchants()
        );

    }

    private static final class FakeMerchantRepository implements MerchantRepository {

        private final List<Merchant> merchants = new ArrayList<>();

        @Override
        public boolean existsByEmail(String normalizedEmail) {
            return merchants.stream()
                .anyMatch(merchant ->
                    merchant.email().equals(normalizedEmail)
                );
        }

        @Override
        public Merchant save(Merchant merchant) {
            merchants.add(merchant);
            return merchant;
        }

        List<Merchant> savedMerchants() {
            return List.copyOf(merchants);
        }
    }

    @Test
    void rejectsRegistrationWhenEmailAlreadyExists() {

        Instant registrationTime = Instant.parse("2026-07-18T10:15:30Z");

        Clock fixedClock = Clock.fixed(
            registrationTime,
            ZoneOffset.UTC
        );

        FakeMerchantRepository repository = new FakeMerchantRepository();
        RegisterMerchantService service = new RegisterMerchantService(
            repository,
            fixedClock
        );

        RegisterMerchantCommand firstCommand = new RegisterMerchantCommand(
                "FreshBrew Cafe",
                "Owner@FreshBrew.Example",
                "IN",
                "INR"
            );

        RegisterMerchantCommand secondCommand = new RegisterMerchantCommand(
                "FreshBrew Express",
                " owner@freshbrew.example ",
                "IN",
                "INR"
            );

        service.register(firstCommand);
        assertThrows(MerchantEmailAlreadyExistsException.class,
            () -> service.register(secondCommand)
        );

        assertEquals(
            1,
            repository.savedMerchants().size()
        );
    }

    @Test
    void rejectsNullRegistrationCommand() {

        Clock fixedClock = Clock.fixed(Instant.parse("2026-07-18T10:15:30Z"), ZoneOffset.UTC);

        FakeMerchantRepository repository = new FakeMerchantRepository();

        RegisterMerchantService service = new RegisterMerchantService(repository, fixedClock);

        assertThrows(
            IllegalArgumentException.class,
            () -> service.register(null)
        );

    }
}
