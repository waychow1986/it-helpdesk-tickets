package com.ithelpdesk.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TicketFormValidationTest {

    @Test
    void eachMissingFieldReportedSeparately() {
        assertThat(new TicketForm("", "d", "1", "LOW").validate()).containsExactly(Map.entry("title", "请填写标题"));
        assertThat(new TicketForm("t", "  ", "1", "LOW").validate())
            .containsExactly(Map.entry("description", "请填写问题描述"));
        assertThat(new TicketForm("t", "d", "", "LOW").validate())
            .containsExactly(Map.entry("categoryId", "请选择类别"));
        assertThat(new TicketForm("t", "d", "1", null).validate())
            .containsExactly(Map.entry("urgency", "请选择紧急程度"));
        assertThat(new TicketForm(" \t", "\n", null, " ").validate())
            .containsOnlyKeys("title", "description", "categoryId", "urgency");
    }

    @Test
    void overlongRejected() {
        assertThat(new TicketForm("x".repeat(201), "d", "1", "LOW").validate()).containsKey("title");
        assertThat(new TicketForm("t", "x".repeat(10001), "1", "LOW").validate()).containsKey("description");
        assertThat(new TicketForm("x".repeat(200), "x".repeat(10000), "1", "LOW").validate()).isEmpty();
    }

    @Test
    void urgencyOnlyLowMediumHigh() {
        assertThat(Urgency.values()).extracting(Urgency::getLabel).containsExactly("低", "中", "高");
        for (String ok : new String[] {"LOW", "MEDIUM", "HIGH"}) {
            assertThat(new TicketForm("t", "d", "1", ok).validate()).isEmpty();
        }
        assertThat(new TicketForm("t", "d", "1", "URGENT").validate()).containsKey("urgency");
    }

    @Test
    void invalidCategoryIdTreatedAsMissing() {
        assertThat(new TicketForm("t", "d", "abc", "LOW").validate()).containsKey("categoryId");
        assertThat(new TicketForm("t", "d", "-3", "LOW").validate()).containsKey("categoryId");
    }

    @Test
    void failedFormKeepsEnteredValues() {
        TicketForm form = new TicketForm("打印机卡纸", "", "5", "HIGH");
        assertThat(form.validate()).containsOnlyKeys("description");
        assertThat(form.getTitle()).isEqualTo("打印机卡纸");
        assertThat(form.getCategoryId()).isEqualTo("5");
        assertThat(form.getUrgency()).isEqualTo("HIGH");
    }
}
