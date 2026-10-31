package com.moveinsync.roster.deactivation;

import com.moveinsync.roster.core.ownership.FieldKey;
import com.moveinsync.roster.domain.Booking;
import com.moveinsync.roster.domain.BookingStatus;
import com.moveinsync.roster.domain.Employee;
import com.moveinsync.roster.domain.EmployeeStatus;
import com.moveinsync.roster.employee.EmployeeRepository;
import com.moveinsync.roster.observability.RosterMetrics;
import com.moveinsync.roster.outbox.OutboxEventType;
import com.moveinsync.roster.outbox.OutboxWriter;
import com.moveinsync.roster.security.TenantContext;
import com.moveinsync.roster.web.ResourceNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class EmployeeDeactivationService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeDeactivationService.class);

    private final EmployeeRepository employees;
    private final BookingRepository bookings;
    private final TripRepository trips;
    private final OutboxWriter outboxWriter;
    private final RosterMetrics metrics;

    public EmployeeDeactivationService(EmployeeRepository employees, BookingRepository bookings, TripRepository trips,
                                       OutboxWriter outboxWriter, RosterMetrics metrics) {
        this.employees = employees;
        this.bookings = bookings;
        this.trips = trips;
        this.outboxWriter = outboxWriter;
        this.metrics = metrics;
    }

    @Transactional
    public boolean deactivate(long employeeId, DeactivationReason reason, DeactivationTrigger trigger) {
        long tenantId = TenantContext.require();
        Employee employee = employees.findForUpdateByTenantIdAndId(tenantId, employeeId)
                .orElseThrow(ResourceNotFoundException::new);
        if (employee.getStatus() == EmployeeStatus.INACTIVE) {
            return false;
        }

        Instant now = Instant.now();
        employee.setStatus(EmployeeStatus.INACTIVE);
        employee.setDeactivatedAt(now);
        outboxWriter.write(employee, OutboxEventType.EMPLOYEE_DEACTIVATED, List.of(FieldKey.STATUS), now);

        List<Booking> futureBookings = bookings.findFutureBooked(tenantId, employeeId, LocalDate.now(ZoneOffset.UTC));
        for (Booking booking : futureBookings) {
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelReason(reason.name());
            booking.setCancelledAt(now);
            if (trips.releaseSeat(booking.getTrip().getId()) == 0) {
                log.error("SEAT_COUNT_DRIFT booking {} on trip {} had no seat to release",
                        booking.getId(), booking.getTrip().getId());
            }
            outboxWriter.writeBookingCancelled(employee, booking, now);
        }
        log.info("Deactivated employee {}, reason {}, trigger {}, {} future booking(s) cancelled",
                employee.getExternalId(), reason, trigger, futureBookings.size());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                metrics.deactivated(trigger);
            }
        });
        return true;
    }
}
