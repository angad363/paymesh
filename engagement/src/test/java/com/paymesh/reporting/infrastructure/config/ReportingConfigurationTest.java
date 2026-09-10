package com.paymesh.reporting.infrastructure.config;

import com.paymesh.TestcontainersConfiguration;
import com.paymesh.reporting.application.GenerateReportExportsService;
import com.paymesh.reporting.application.GetReportExportService;
import com.paymesh.reporting.application.GetReportsService;
import com.paymesh.reporting.application.RecordReportFactService;
import com.paymesh.reporting.application.ReportExportRepository;
import com.paymesh.reporting.application.ReportFactRepository;
import com.paymesh.reporting.application.RequestReportExportService;
import com.paymesh.reporting.domain.ReportFact;
import com.paymesh.reporting.infrastructure.events.ReportFactHandler;
import com.paymesh.reporting.infrastructure.persistence.jpa.JpaReportExportRepository;
import com.paymesh.reporting.infrastructure.persistence.jpa.JpaReportFactRepository;
import com.paymesh.reporting.infrastructure.schedule.ReportExportGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beans are wired by hand, not component-scanned, so "is it wired" is a real question answered here.
 * A missing {@code @Bean} is otherwise a startup failure that surfaces in whichever test boots a
 * context first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("dev")
class ReportingConfigurationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void wiresTheRepositoryAdaptersAndServices() {
        assertThat(context.getBean(ReportFactRepository.class))
            .isInstanceOf(JpaReportFactRepository.class);
        assertThat(context.getBean(ReportExportRepository.class))
            .isInstanceOf(JpaReportExportRepository.class);
        assertThat(context.getBean(RecordReportFactService.class)).isNotNull();
        assertThat(context.getBean(GetReportsService.class)).isNotNull();
        assertThat(context.getBean(RequestReportExportService.class)).isNotNull();
        assertThat(context.getBean(GetReportExportService.class)).isNotNull();
        assertThat(context.getBean(GenerateReportExportsService.class)).isNotNull();
    }

    /**
     * ONE HANDLER PER SUBSCRIBED TYPE, AND THE SET MUST BE EXACTLY THE DOMAIN'S. This is the fourth
     * of the four things that must move together when a seventh event is added -- the constructor
     * catches a type the extractor cannot read, the extractor test catches the extractor and the
     * domain disagreeing, and the migration's CHECK catches a row of the wrong type, but nothing but
     * this catches a handler that was never registered.
     */
    @Test
    void subscribesToExactlyTheProjectedTypes() {
        List<String> types = context.getBeansOfType(ReportFactHandler.class).values().stream()
            .map(ReportFactHandler::eventType)
            .toList();

        assertThat(types).containsExactlyInAnyOrderElementsOf(ReportFact.SUBSCRIBED_TYPES);
    }

    /**
     * THE ASSERTION THIS CLASS EXISTS FOR. {@code dev} is the profile every {@code @SpringBootTest}
     * runs under; a timer flipping an export to COMPLETED while another test asserts it is PENDING is
     * a flake. {@code @ConditionalOnProperty} makes the bean ABSENT rather than present-and-idle, so
     * this is a bean-count assertion and not a flag check.
     */
    @Test
    void doesNotRegisterTheExportTimerUnderTheDevelopmentProfile() {
        assertThat(context.getBeanNamesForType(ReportExportGenerator.class))
            .as("paymesh.reporting.export.enabled is false in application-dev.yaml")
            .isEmpty();
    }

    @Test
    void bindsTheExportTuning() {
        ReportExportProperties properties = context.getBean(ReportExportProperties.class);

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.batchSize()).isPositive();
        assertThat(properties.maxRows()).isPositive();
    }
}
