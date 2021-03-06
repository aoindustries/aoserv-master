/*
 * aoserv-master - Master server for the AOServ Platform.
 * Copyright (C) 2001-2013, 2017, 2019, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-master.
 *
 * aoserv-master is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-master is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-master.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.master;

import com.aoapps.collections.IntList;

/**
 * Invalidate requests are place into a queue and then processed by concurrent threads.
 * The requests are temporarily stored in <code>InvalidateCacheEntry</code> objects.
 *
 * @author  AO Industries, Inc.
 */
final public class InvalidateCacheEntry {

	private final IntList invalidateList;
	private final int host;
	private final Long cacheSyncID;

	public InvalidateCacheEntry(
		IntList invalidateList,
		int host,
		Long cacheSyncID
	) {
		this.invalidateList = invalidateList;
		this.host = host;
		this.cacheSyncID = cacheSyncID;
	}

	public IntList getInvalidateList() {
		return invalidateList;
	}

	public int getHost() {
		return host;
	}

	public Long getCacheSyncID() {
		return cacheSyncID;
	}
}
