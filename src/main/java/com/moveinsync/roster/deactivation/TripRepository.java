package com.moveinsync.roster.deactivation;

import com.moveinsync.roster.domain.Trip;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends Repository<Trip, Long> {

    Trip save(Trip trip);

    @Modifying
    @Query("UPDATE Trip t SET t.seatsTaken = t.seatsTaken - 1 WHERE t.id = :tripId AND t.seatsTaken > 0")
    int releaseSeat(@Param("tripId") Long tripId);
}
