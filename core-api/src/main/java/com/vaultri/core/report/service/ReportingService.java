package com.vaultri.core.report.service;

import com.vaultri.core.deal.entity.DealStatus;
import com.vaultri.core.reminder.entity.ReminderKind;
import com.vaultri.core.reminder.entity.ReminderStatus;
import com.vaultri.core.report.dto.MonthlyDealsReport;
import com.vaultri.core.report.dto.RemindersReportResponse;
import com.vaultri.core.report.dto.SummaryReportResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.Record5;
import org.jooq.Result;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jooq.impl.DSL.coalesce;
import static org.jooq.impl.DSL.count;
import static org.jooq.impl.DSL.countDistinct;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.sum;
import static org.jooq.impl.DSL.table;
import static org.jooq.impl.DSL.val;
import static org.jooq.impl.DSL.when;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportingService {

    private final DSLContext dsl;

    public SummaryReportResponse getSummary(UUID sellerId, Instant start, Instant end) {
        // Query 1: deals count per status in range
        Result<Record2<String, Integer>> statusCounts = dsl
                .select(
                        field("deals.status", String.class),
                        count()
                )
                .from(table("deals"))
                .where(field("deals.seller_id", UUID.class).eq(val(sellerId)))
                .and(field("deals.created_at", Instant.class).ge(val(start)))
                .and(field("deals.created_at", Instant.class).lt(val(end)))
                .groupBy(field("deals.status", String.class))
                .fetch();

        Map<String, Long> dealsByStatus = new LinkedHashMap<>();
        for (DealStatus status : DealStatus.values()) {
            dealsByStatus.put(status.name(), 0L);
        }
        long totalDeals = 0L;
        for (Record2<String, Integer> r : statusCounts) {
            String st = r.value1();
            long cnt = r.value2() != null ? r.value2().longValue() : 0L;
            dealsByStatus.put(st, cnt);
            totalDeals += cnt;
        }
        long cancelledDealsCount = dealsByStatus.getOrDefault(DealStatus.CANCELLED.name(), 0L);

        // Query 2: total deal value in range
        Long totalDealValue = dsl
                .select(coalesce(sum(field("deals.price", Long.class)), val(0L)))
                .from(table("deals"))
                .where(field("deals.seller_id", UUID.class).eq(val(sellerId)))
                .and(field("deals.created_at", Instant.class).ge(val(start)))
                .and(field("deals.created_at", Instant.class).lt(val(end)))
                .fetchOne(0, Long.class);
        if (totalDealValue == null) {
            totalDealValue = 0L;
        }

        // Query 3: total deposits paid and cancellation fees collected from ledger entries
        Result<Record2<String, Long>> ledgerSums = dsl
                .select(
                        field("ledger_entries.type", String.class),
                        coalesce(sum(field("ledger_entries.amount", Long.class)), val(0L)).cast(Long.class)
                )
                .from(table("ledger_entries"))
                .join(table("deals")).on(field("ledger_entries.deal_id", UUID.class).eq(field("deals.id", UUID.class)))
                .where(field("deals.seller_id", UUID.class).eq(val(sellerId)))
                .and(field("deals.created_at", Instant.class).ge(val(start)))
                .and(field("deals.created_at", Instant.class).lt(val(end)))
                .groupBy(field("ledger_entries.type", String.class))
                .fetch();

        long totalDepositsPaid = 0L;
        long totalCancellationFees = 0L;
        for (Record2<String, Long> r : ledgerSums) {
            String type = r.value1();
            long amt = r.value2() != null ? r.value2() : 0L;
            if ("DEPOSIT".equalsIgnoreCase(type)) {
                totalDepositsPaid += amt;
            } else if ("CANCELLATION_FEE".equalsIgnoreCase(type)) {
                totalCancellationFees += amt;
            }
        }

        return new SummaryReportResponse(
                dealsByStatus,
                totalDeals,
                totalDealValue,
                totalDepositsPaid,
                totalCancellationFees,
                cancelledDealsCount
        );
    }

    public List<MonthlyDealsReport> getDealsByMonth(UUID sellerId, Instant start, Instant end) {
        Field<String> monthField = field("to_char(timezone('UTC', deals.created_at), 'YYYY-MM')", String.class);
        Field<Long> dealsCreatedField = countDistinct(field("deals.id", UUID.class)).cast(Long.class);
        Field<Long> dealsCompletedField = countDistinct(
                when(field("deals.status", String.class).eq(val("COMPLETED")), field("deals.id", UUID.class))
        ).cast(Long.class);
        Field<Long> dealsCancelledField = countDistinct(
                when(field("deals.status", String.class).eq(val("CANCELLED")), field("deals.id", UUID.class))
        ).cast(Long.class);
        Field<Long> depositsCollectedField = coalesce(
                sum(when(field("ledger_entries.type", String.class).eq(val("DEPOSIT")), field("ledger_entries.amount", Long.class)).otherwise(val(0L))),
                val(0L)
        ).cast(Long.class);

        Result<Record5<String, Long, Long, Long, Long>> records = dsl
                .select(
                        monthField.as("month"),
                        dealsCreatedField.as("deals_created"),
                        dealsCompletedField.as("deals_completed"),
                        dealsCancelledField.as("deals_cancelled"),
                        depositsCollectedField.as("deposits_collected")
                )
                .from(table("deals"))
                .leftJoin(table("ledger_entries")).on(
                        field("ledger_entries.deal_id", UUID.class).eq(field("deals.id", UUID.class))
                                .and(field("ledger_entries.type", String.class).eq(val("DEPOSIT")))
                )
                .where(field("deals.seller_id", UUID.class).eq(val(sellerId)))
                .and(field("deals.created_at", Instant.class).ge(val(start)))
                .and(field("deals.created_at", Instant.class).lt(val(end)))
                .groupBy(monthField)
                .orderBy(monthField.asc())
                .fetch();

        List<MonthlyDealsReport> results = new ArrayList<>();
        for (Record5<String, Long, Long, Long, Long> r : records) {
            results.add(new MonthlyDealsReport(
                    r.value1(),
                    r.value2() != null ? r.value2() : 0L,
                    r.value3() != null ? r.value3() : 0L,
                    r.value4() != null ? r.value4() : 0L,
                    r.value5() != null ? r.value5() : 0L
            ));
        }
        return results;
    }

    public RemindersReportResponse getReminders(UUID sellerId) {
        Result<Record3<String, String, Integer>> records = dsl
                .select(
                        field("reminders.status", String.class),
                        field("reminders.kind", String.class),
                        count()
                )
                .from(table("reminders"))
                .join(table("deals")).on(field("reminders.deal_id", UUID.class).eq(field("deals.id", UUID.class)))
                .where(field("deals.seller_id", UUID.class).eq(val(sellerId)))
                .groupBy(
                        field("reminders.status", String.class),
                        field("reminders.kind", String.class)
                )
                .fetch();

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ReminderStatus st : ReminderStatus.values()) {
            byStatus.put(st.name(), 0L);
        }

        Map<String, Long> byKind = new LinkedHashMap<>();
        for (ReminderKind kd : ReminderKind.values()) {
            byKind.put(kd.name(), 0L);
        }

        Map<String, Map<String, Long>> byStatusAndKind = new LinkedHashMap<>();
        for (ReminderStatus st : ReminderStatus.values()) {
            Map<String, Long> kindMap = new LinkedHashMap<>();
            for (ReminderKind kd : ReminderKind.values()) {
                kindMap.put(kd.name(), 0L);
            }
            byStatusAndKind.put(st.name(), kindMap);
        }

        long total = 0L;
        for (Record3<String, String, Integer> r : records) {
            String status = r.value1();
            String kind = r.value2();
            long cnt = r.value3() != null ? r.value3().longValue() : 0L;

            byStatus.put(status, byStatus.getOrDefault(status, 0L) + cnt);
            byKind.put(kind, byKind.getOrDefault(kind, 0L) + cnt);

            if (byStatusAndKind.containsKey(status)) {
                byStatusAndKind.get(status).put(kind, cnt);
            }
            total += cnt;
        }

        return new RemindersReportResponse(byStatus, byKind, byStatusAndKind, total);
    }
}
