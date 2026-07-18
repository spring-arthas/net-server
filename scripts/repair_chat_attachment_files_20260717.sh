#!/usr/bin/env bash
set -euo pipefail

MODE="${1:---dry-run}"
if [[ "$MODE" != "--dry-run" && "$MODE" != "--apply" ]]; then
  echo "Usage: $0 [--dry-run|--apply]" >&2
  exit 2
fi

: "${DB_HOST:?DB_HOST is required}"
: "${DB_PORT:?DB_PORT is required}"
: "${DB_USER:?DB_USER is required}"
: "${DB_NAME:?DB_NAME is required}"
: "${MYSQL_PWD:?MYSQL_PWD is required}"

STORAGE_ROOT="${STORAGE_ROOT:-/Users/hljy/Documents/storages}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SQL_FILE="$SCRIPT_DIR/../sql/repair_chat_attachment_parent_ids_20260717.sql"
MYSQL=(mysql --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" -D "$DB_NAME" --default-character-set=utf8mb4 --batch --raw)

FILE_IDS="7471493778193281024,7471494081969942528,7471510898411077632,7471510901426782208,7471510903746232320,7471510905759498240,7471510908666150912,7471510911010766848,7471510912571047936,7471510914668199936,7471510920267595776,7471510923077779456,7471510925481115648,7471510927490187264,7471515448564031488,7471515454377336832,7471515456906502144,7471515459431473152,7471515461746728960,7471515463139237888,7471515464619827200,7471515466469515264,7471515470772871168,7471553120644907008,7471553127687143424,7471553129687826432,7471553131927584768,7471553134985232384,7471553136914612224"
CORRUPT_PREVIEW_ID="7471510911010766848"
CORRUPT_PREVIEW_PATH="$STORAGE_ROOT/2026/07/02/b5bf313db07f421e83ddc325459beba5_chat-preview-36DD7722-41E1-4233-9720-8ACE9AB11ADE.jpg"
SOURCE_ORIGINAL_PATH="$STORAGE_ROOT/2026/07/02/606f99f168dd47cca848037fdffb3840_cf8c5f86-d645-4661-a692-910209bd65e0-1780644508209.jpg"

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/chat-attachment-repair.XXXXXX")"
ROWS_FILE="$WORK_DIR/files.tsv"
MOVED_FILE="$WORK_DIR/moved.tsv"
trap 'rm -rf "$WORK_DIR"' EXIT

"${MYSQL[@]}" --skip-column-names -e "
SELECT id, user_name, file_path, file_size
FROM file
WHERE id IN ($FILE_IDS)
  AND is_file='Y' AND is_exist='Y' AND del='N'
  AND parent_id IN (0,-1) AND user_id IS NULL
ORDER BY id;
" > "$ROWS_FILE"

row_count="$(wc -l < "$ROWS_FILE" | tr -d ' ')"
if [[ "$row_count" != "29" ]]; then
  echo "Preflight failed: expected 29 invalid attachment rows, found $row_count" >&2
  exit 1
fi

printf 'Preflight: validating %s database rows and physical files\n' "$row_count"
while IFS=$'\t' read -r file_id user_name old_path expected_size; do
  if [[ ! -f "$old_path" ]]; then
    echo "Missing physical file: id=$file_id path=$old_path" >&2
    exit 1
  fi
  actual_size="$(/usr/bin/stat -f '%z' "$old_path")"
  if [[ "$file_id" != "$CORRUPT_PREVIEW_ID" && "$actual_size" != "$expected_size" ]]; then
    echo "Size mismatch: id=$file_id expected=$expected_size actual=$actual_size" >&2
    exit 1
  fi
  case "$user_name" in
    18806504525|15757821544) ;;
    *) echo "Unexpected attachment owner: id=$file_id user=$user_name" >&2; exit 1 ;;
  esac
done < "$ROWS_FILE"

reference_count="$("${MYSQL[@]}" --skip-column-names -e "
SELECT COUNT(*) FROM user_friend_message
WHERE id IN (225,226,227,228,229) AND del='N';
")"
if [[ "$reference_count" != "5" ]]; then
  echo "Preflight failed: expected 5 chat message references, found $reference_count" >&2
  exit 1
fi

if [[ "$MODE" == "--dry-run" ]]; then
  echo "Dry-run passed: 29 files and 5 messages are ready for migration"
  exit 0
fi

BACKUP_DIR="$STORAGE_ROOT/.migration-backups/chat-attachment-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
cp "$ROWS_FILE" "$BACKUP_DIR/file-records-before.tsv"
"${MYSQL[@]}" -e "SELECT * FROM user_friend_message WHERE id IN (225,226,227,228,229) ORDER BY id;" \
  > "$BACKUP_DIR/messages-before.tsv"
cp "$CORRUPT_PREVIEW_PATH" "$BACKUP_DIR/corrupt-preview-before.jpg"

RECOVERED_PREVIEW="$WORK_DIR/recovered-preview.jpg"
/usr/bin/sips -s format jpeg -s formatOptions 90 "$SOURCE_ORIGINAL_PATH" --out "$RECOVERED_PREVIEW" >/dev/null
/usr/bin/sips -g pixelWidth -g pixelHeight -g format "$RECOVERED_PREVIEW" >/dev/null
recovered_preview_size="$(/usr/bin/stat -f '%z' "$RECOVERED_PREVIEW")"
if [[ "$recovered_preview_size" -le 0 ]]; then
  echo "Recovered preview is empty" >&2
  exit 1
fi
cp "$RECOVERED_PREVIEW" "$CORRUPT_PREVIEW_PATH"

mkdir -p "$STORAGE_ROOT/18806504525/.chat-attachments"
mkdir -p "$STORAGE_ROOT/15757821544/.chat-attachments"

rollback_files() {
  if [[ ! -s "$MOVED_FILE" ]]; then
    cp "$BACKUP_DIR/corrupt-preview-before.jpg" "$CORRUPT_PREVIEW_PATH"
    return
  fi
  while IFS=$'\t' read -r old_path new_path; do
    if [[ -f "$new_path" && ! -e "$old_path" ]]; then
      mv "$new_path" "$old_path"
    fi
  done < <(tail -r "$MOVED_FILE")
  cp "$BACKUP_DIR/corrupt-preview-before.jpg" "$CORRUPT_PREVIEW_PATH"
}

: > "$MOVED_FILE"
while IFS=$'\t' read -r file_id user_name old_path expected_size; do
  new_path="$STORAGE_ROOT/$user_name/.chat-attachments/$(basename "$old_path")"
  if [[ -e "$new_path" ]]; then
    echo "Destination already exists: $new_path" >&2
    rollback_files
    exit 1
  fi
  printf '%s\t%s\t%s\n' "$file_id" "$old_path" "$new_path" >> "$BACKUP_DIR/path-manifest.tsv"
  mv "$old_path" "$new_path"
  printf '%s\t%s\n' "$old_path" "$new_path" >> "$MOVED_FILE"
done < "$ROWS_FILE"

if ! "${MYSQL[@]}" --init-command="SET @recovered_preview_size=$recovered_preview_size" < "$SQL_FILE"; then
  echo "Database migration failed; restoring moved files" >&2
  rollback_files
  exit 1
fi

invalid_after="$("${MYSQL[@]}" --skip-column-names -e "
SELECT COUNT(*) FROM file
WHERE id IN ($FILE_IDS)
  AND (parent_id <= 0 OR user_id IS NULL OR file_path NOT LIKE CONCAT('/Users/hljy/Documents/storages/', user_name, '/.chat-attachments/%'));
")"
if [[ "$invalid_after" != "0" ]]; then
  echo "Post-migration database verification failed: invalid rows=$invalid_after" >&2
  exit 1
fi

"${MYSQL[@]}" --skip-column-names -e "
SELECT id, file_path, file_size FROM file WHERE id IN ($FILE_IDS) ORDER BY id;
" | while IFS=$'\t' read -r file_id file_path file_size; do
  [[ -f "$file_path" ]] || { echo "Post-check missing file: id=$file_id" >&2; exit 1; }
  actual_size="$(/usr/bin/stat -f '%z' "$file_path")"
  [[ "$actual_size" == "$file_size" ]] || {
    echo "Post-check size mismatch: id=$file_id db=$file_size fs=$actual_size" >&2
    exit 1
  }
done

rmdir "$STORAGE_ROOT/2026/07/02" 2>/dev/null || true
rmdir "$STORAGE_ROOT/2026/07" 2>/dev/null || true
rmdir "$STORAGE_ROOT/2026" 2>/dev/null || true

echo "Migration completed: 29 attachment files moved; backup=$BACKUP_DIR"

