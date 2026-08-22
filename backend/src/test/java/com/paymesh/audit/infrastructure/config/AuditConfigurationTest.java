package com.paymesh.audit.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.audit.application.AuditEventRepository;
import com.paymesh.audit.application.AuditExportRepository;
import com.paymesh.audit.application.GenerateAuditExportsService;
import com.paymesh.audit.application.GetAuditEventService;
import com.paymesh.audit.application.GetAuditExportService;
import com.paymesh.audit.application.ListAuditEventsService;
import com.paymesh.audit.application.RecordAuditEventService;
import com.paymesh.audit.application.RequestAuditExportService;
import com.paymesh.audit.infrastructure.AuditRecorderAdapter;
import com.paymesh.audit.infrastructure.persistence.jpa.JpaAuditEventRepository;
import com.paymesh.audit.infrastructure.persistence.jpa.JpaAuditExportRepository;
import com.paymesh.audit.infrastructure.schedule.AuditExportGenerator;
import com.paymesh.shared.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beans are wired by hand, not component-scanned, so "is it wired" is a real question answered here.
 * The {@link AuditRecorder} bean matters most: Merchant, Identity and Webhook receive it by
 * interface type, so a missing bean is a startup failure across three capabilities at once.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class AuditConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheRepositoriesServicesAndTheSharedRecorderPort() {
        assertThat(context.getBean(AuditEventRepository.class))
            .isInstanceOf(JpaAuditEventRepository.class);
        assertThat(context.getBean(AuditExportRepository.class))
            .isInstanceOf(JpaAuditExportRepository.class);
        assertThat(context.getBean(AuditRecorder.class))
            .isInstanceOf(AuditRecorderAdapter.class);
        assertThat(context.getBean(RecordAuditEventService.class)).isNotNull();
        assertThat(context.getBean(ListAuditEventsService.class)).isNotNull();
        assertThat(context.getBean(GetAuditEventService.class)).isNotNull();
        assertThat(context.getBean(RequestAuditExportService.class)).isNotNull();
        assertThat(context.getBean(GetAuditExportService.class)).isNotNull();
        assertThat(context.getBean(GenerateAuditExportsService.class)).isNotNull();
    }

    /**
     * THE ASSERTION THIS CLASS EXISTS FOR. {@code dev} is the profile every {@code @SpringBootTest}
     * runs under; a timer flipping an export to COMPLETED while another test asserts PENDING is a
     * flake. {@code @ConditionalOnProperty} makes the bean ABSENT, so this is a bean-count assertion.
     */
    @Test
    void doesNotRegisterTheExportTimerUnderTheDevelopmentProfile() {
        assertThat(context.getBeanNamesForType(AuditExportGenerator.class))
            .as("paymesh.audit.export.enabled is false in application-dev.yaml")
            .isEmpty();
    }
}
