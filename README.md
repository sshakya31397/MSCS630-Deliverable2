# Custom Java Shell – Deliverable 2: Process Scheduling

## Overview
This phase extends the shell to simulate CPU scheduling.  
Two algorithms are implemented:
- **Round-Robin Scheduling (RR)** with configurable time quantum.
- **Priority-Based Scheduling** with preemption and FCFS tie-breaking.

## Requirements
- Java 11 or newer
- Works on Linux, macOS, and Windows

## Compilation
```bash
javac MyShell.java
```

## Running the Shell
```bash
java MyShell
```

## New Commands
### Round-Robin
```bash
rr <quantum>
```
Example:
```bash
rr 2
```
Runs the scheduler with quantum = 2 ticks (1 tick = 100 ms).

### Priority Scheduling
```bash
priority
```
Runs preemptive priority scheduling on a demo set of processes.

## Example Output
- Timeline showing when each process runs or is preempted
- Metrics table with Waiting, Turnaround, and Response times
- Average metrics displayed at the end

## Metrics Definitions
- **Waiting Time** = Turnaround Time – Burst Time  
- **Turnaround Time** = Completion Time – Arrival Time  
- **Response Time** = Start Time – Arrival Time  

## Screenshots to Provide
- Round-Robin run with different quantums
- Priority scheduling with preemption
- Metrics tables for both algorithms

## Known Limitations
- Simulation uses `Thread.sleep()` to represent ticks; not real OS scheduling
- No other scheduling algorithms (SJF, multilevel) yet
- Aging not yet implemented for starvation prevention

## Author
Sauhard Shakya
