package com.voyre.app;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface LocationDao {
    @Insert
    void insert(LocationEntity location);

    @Query("SELECT * FROM location_table ORDER BY timestamp ASC")
    List<LocationEntity> getAllLocations();

    @Query("DELETE FROM location_table")
    void deleteAll();
}
