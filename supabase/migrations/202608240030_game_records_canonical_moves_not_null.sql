-- Finalize the online Game Record contract. New result submissions already reject
-- NULL canonical moves; this makes the persisted Game Record schema agree with it.
do $$
begin
  if exists (select 1 from public.game_records where canonical_moves is null) then
    raise exception 'game_records.canonical_moves contains NULL rows; clean them before applying 030';
  end if;
end $$;

alter table public.game_records
  alter column canonical_moves set not null;
