import {UserManager, WebStorageStateStore} from 'oidc-client-ts';
const authority=import.meta.env.VITE_OIDC_AUTHORITY || '';
const client_id=import.meta.env.VITE_OIDC_CLIENT_ID || '';
export const configured=Boolean(authority && client_id);
// Browser session storage only; no localStorage or embedded credentials.
export const userManager=configured?new UserManager({authority,client_id,redirect_uri:window.location.origin+'/auth/callback',post_logout_redirect_uri:window.location.origin+'/',response_type:'code',scope:'openid profile',userStore:new WebStorageStateStore({store:window.sessionStorage}),automaticSilentRenew:false}):null;
export async function getAccessToken(){const user=await userManager?.getUser();if(!user||user.expired)throw new Error('OIDC сессия неактивна. Войдите снова.');return user.access_token;}
