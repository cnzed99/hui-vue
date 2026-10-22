# Disk management
User constraint: system disk is only 60 GB. Check usage before and after every installation, build and test batch. Keep at least 10 GiB free before large operations; stop and assess if below threshold.
Preserve source, database, active dependency caches, useful diagnostics and recoverable backup. Remove verified obsolete installers, duplicate extracted archives and temporary test outputs using explicit paths, never broad wildcard cleanup. Rotate logs. Do not represent swap as physical memory. Record before/after disk usage and actual cleanup effects.
