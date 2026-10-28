// The project list's address parameters (project-list.html, served at /projects?…). Any one of them on
// /projects shows the list; on /stories (where the list used to live) they send the visitor to /projects.
export const LIST_KEYS = ['country', 'cat', 'kind', 'status', 'serve', 'region', 'q', 'year', 'pump', 'project', 'view'];
export function isListQuery(search) {
  const sp = new URLSearchParams(search || '');
  return LIST_KEYS.some(k => sp.has(k));
}
