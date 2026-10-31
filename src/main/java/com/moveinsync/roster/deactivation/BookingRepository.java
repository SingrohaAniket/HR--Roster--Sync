package com.moveinsync.roster.deactivation;

import com.moveinsync.roster.domain.Booking;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends Repository<Booking, Long> {

    Booking save(Booking booking);

    @Query("""
            SELECT b FROM Booking b
             WHERE b.tenantId = :tenantId
               AND b.employee.id = :employeeId
               AND b.tripDate >= :today
               AND b.status = com.moveinsync.roster.domain.BookingStatus.BOOKED
             ORDER BY b.trip.id
            """)
    List<Booking> findFutureBooked(@Param("tenantId") Long tenantId,
                                   @Param("employeeId") Long employeeId,
                                   @Param("today") LocalDate today);
}
