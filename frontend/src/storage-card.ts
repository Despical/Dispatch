export function initStorageCard(){
  const card=document.querySelector<HTMLElement>('[data-storage-card]');
  const toggle=document.querySelector<HTMLButtonElement>('[data-storage-toggle]');
  const content=document.querySelector<HTMLElement>('[data-storage-content]');
  if(!card||!toggle||!content)return;
  const key=`dispatch:storage-collapsed:${document.body.dataset.adminId??'local'}`;
  let compact=false;
  const set=(value:boolean)=>{compact=value;card.classList.toggle('is-compact',value);toggle.setAttribute('aria-expanded',String(!value));toggle.title=value?'Expand storage usage':'Minimize storage usage';content.inert=value;try{localStorage.setItem(key,String(value));}catch{/* Keep the control usable when storage is blocked. */}};
  try{set(localStorage.getItem(key)==='true');}catch{set(false);}
  toggle.addEventListener('click',()=>set(!compact));
}
