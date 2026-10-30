#!/bin/bash
# Run as root. A short API stop keeps database/photo snapshots consistent.
# Deliberately no automatic pruning; copy encrypted backups off this server.
set -Eeuo pipefail
umask 077
backup_root=/var/backups/handcats
mkdir -p "$backup_root"
backup_dir=$(mktemp -d "$backup_root/$(date -u +%Y%m%dT%H%M%SZ).XXXXXX")
was_active=false
if systemctl is-active --quiet handcats; then
    was_active=true
    systemctl stop handcats
fi
resume_api() { if "$was_active"; then systemctl start handcats; fi; }
trap resume_api EXIT
runuser -u postgres -- pg_dump --format=custom handcats > "$backup_dir/database.dump"
tar -C /var/lib/handcats -czf "$backup_dir/photos.tar.gz" photos
cp /etc/credstore.encrypted/handcats.env "$backup_dir/backend.env.encrypted"
sha256sum "$backup_dir/database.dump" "$backup_dir/photos.tar.gz" > "$backup_dir/SHA256SUMS"
echo "Backup complete: $backup_dir"
echo "Database and photos contain personal data: encrypt and copy off-host. Test restoration separately."
