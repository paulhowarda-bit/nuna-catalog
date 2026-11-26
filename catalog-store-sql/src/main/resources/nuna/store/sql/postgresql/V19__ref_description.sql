-- Backlog 167: a ref keeps the purpose its maker gave it, one line of plain text the management route bounds
-- (RefRoutes: at most 200 characters, no control or line-breaking characters). NULL is a ref made without one, every
-- ref made before this step among them. A nullable column with no default changes only the table's definition; no
-- previous release writes ref (V11 is newer than it), and this release's servers that do not yet name the column
-- keep making and reading refs while this applies.
ALTER TABLE ref ADD COLUMN description TEXT;
