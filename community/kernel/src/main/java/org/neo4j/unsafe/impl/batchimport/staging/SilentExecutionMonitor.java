/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.unsafe.impl.batchimport.staging;

/**
 * Waits until an execution is completed, but doesn't print anything.
 */
public class SilentExecutionMonitor extends PollingExecutionMonitor
{
    public SilentExecutionMonitor()
    {
        super( 100 );
    }

    @Override
    public void done( long totalTimeMillis )
    {   // We're done
    }

    @Override
    protected void poll( StageExecution[] executions )
    {   // We're still going...
    }
}
