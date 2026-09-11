var Ot=Object.defineProperty;var Nt=(i,e,t)=>e in i?Ot(i,e,{enumerable:!0,configurable:!0,writable:!0,value:t}):i[e]=t;var F=(i,e,t)=>Nt(i,typeof e!="symbol"?e+"":e,t);var We="1.0.0";var de=globalThis,Pe=de.ShadowRoot&&(de.ShadyCSS===void 0||de.ShadyCSS.nativeShadow)&&"adoptedStyleSheets"in Document.prototype&&"replace"in CSSStyleSheet.prototype,Le=Symbol(),Ge=new WeakMap,ce=class{constructor(e,t,r){if(this._$cssResult$=!0,r!==Le)throw Error("CSSResult is not constructable. Use `unsafeCSS` or `css` instead.");this.cssText=e,this.t=t}get styleSheet(){let e=this.i,t=this.t;if(Pe&&e===void 0){let r=t!==void 0&&t.length===1;r&&(e=Ge.get(t)),e===void 0&&((this.i=e=new CSSStyleSheet).replaceSync(this.cssText),r&&Ge.set(t,e))}return e}toString(){return this.cssText}},Mt=i=>new ce(typeof i=="string"?i:i+"",void 0,Le),ut=(i,...e)=>{let t=i.length===1?i[0]:e.reduce((r,s,n)=>r+(o=>{if(o._$cssResult$===!0)return o.cssText;if(typeof o=="number")return o;throw Error("Value passed to 'css' function must be a 'css' function result: "+o+". Use 'unsafeCSS' to pass non-literal values, but take care to ensure page security.")})(s)+i[n+1],i[0]);return new ce(t,i,Le)},Pt=(i,e)=>{if(Pe)i.adoptedStyleSheets=e.map(t=>t instanceof CSSStyleSheet?t:t.styleSheet);else for(let t of e){let r=document.createElement("style"),s=de.litNonce;s!==void 0&&r.setAttribute("nonce",s),r.textContent=t.cssText,i.appendChild(r)}},Ye=Pe?i=>i:i=>i instanceof CSSStyleSheet?(e=>{let t="";for(let r of e.cssRules)t+=r.cssText;return Mt(t)})(i):i,{is:Lt,defineProperty:Ht,getOwnPropertyDescriptor:Dt,getOwnPropertyNames:Kt,getOwnPropertySymbols:Ut,getPrototypeOf:jt}=Object,V=globalThis,Je=V.trustedTypes,It=Je?Je.emptyScript:"",zt=V.reactiveElementPolyfillSupport,J=(i,e)=>i,Oe={toAttribute(i,e){switch(e){case Boolean:i=i?It:null;break;case Object:case Array:i=i==null?i:JSON.stringify(i)}return i},fromAttribute(i,e){let t=i;switch(e){case Boolean:t=i!==null;break;case Number:t=i===null?null:Number(i);break;case Object:case Array:try{t=JSON.parse(i)}catch{t=null}}return t}},pt=(i,e)=>!Lt(i,e),Xe={attribute:!0,type:String,converter:Oe,reflect:!1,useDefault:!1,hasChanged:pt};Symbol.metadata??(Symbol.metadata=Symbol("metadata")),V.litPropertyMetadata??(V.litPropertyMetadata=new WeakMap);var E=class extends HTMLElement{static addInitializer(e){this.o(),(this.l??(this.l=[])).push(e)}static get observedAttributes(){return this.finalize(),this.u&&[...this.u.keys()]}static createProperty(e,t=Xe){if(t.state&&(t.attribute=!1),this.o(),this.prototype.hasOwnProperty(e)&&((t=Object.create(t)).wrapped=!0),this.elementProperties.set(e,t),!t.noAccessor){let r=Symbol(),s=this.getPropertyDescriptor(e,r,t);s!==void 0&&Ht(this.prototype,e,s)}}static getPropertyDescriptor(e,t,r){let{get:s,set:n}=Dt(this.prototype,e)??{get(){return this[t]},set(o){this[t]=o}};return{get:s,set(o){let a=s?.call(this);n?.call(this,o),this.requestUpdate(e,a,r)},configurable:!0,enumerable:!0}}static getPropertyOptions(e){return this.elementProperties.get(e)??Xe}static o(){if(this.hasOwnProperty(J("elementProperties")))return;let e=jt(this);e.finalize(),e.l!==void 0&&(this.l=[...e.l]),this.elementProperties=new Map(e.elementProperties)}static finalize(){if(this.hasOwnProperty(J("finalized")))return;if(this.finalized=!0,this.o(),this.hasOwnProperty(J("properties"))){let t=this.properties,r=[...Kt(t),...Ut(t)];for(let s of r)this.createProperty(s,t[s])}let e=this[Symbol.metadata];if(e!==null){let t=litPropertyMetadata.get(e);if(t!==void 0)for(let[r,s]of t)this.elementProperties.set(r,s)}this.u=new Map;for(let[t,r]of this.elementProperties){let s=this.p(t,r);s!==void 0&&this.u.set(s,t)}this.elementStyles=this.finalizeStyles(this.styles)}static finalizeStyles(e){let t=[];if(Array.isArray(e)){let r=new Set(e.flat(1/0).reverse());for(let s of r)t.unshift(Ye(s))}else e!==void 0&&t.push(Ye(e));return t}static p(e,t){let r=t.attribute;return r===!1?void 0:typeof r=="string"?r:typeof e=="string"?e.toLowerCase():void 0}constructor(){super(),this.v=void 0,this.isUpdatePending=!1,this.hasUpdated=!1,this.m=null,this._()}_(){this.S=new Promise(e=>this.enableUpdating=e),this._$AL=new Map,this.$(),this.requestUpdate(),this.constructor.l?.forEach(e=>e(this))}addController(e){(this.P??(this.P=new Set)).add(e),this.renderRoot!==void 0&&this.isConnected&&e.hostConnected?.()}removeController(e){this.P?.delete(e)}$(){let e=new Map,t=this.constructor.elementProperties;for(let r of t.keys())this.hasOwnProperty(r)&&(e.set(r,this[r]),delete this[r]);e.size>0&&(this.v=e)}createRenderRoot(){let e=this.shadowRoot??this.attachShadow(this.constructor.shadowRootOptions);return Pt(e,this.constructor.elementStyles),e}connectedCallback(){this.renderRoot??(this.renderRoot=this.createRenderRoot()),this.enableUpdating(!0),this.P?.forEach(e=>e.hostConnected?.())}enableUpdating(e){}disconnectedCallback(){this.P?.forEach(e=>e.hostDisconnected?.())}attributeChangedCallback(e,t,r){this._$AK(e,r)}C(e,t){let r=this.constructor.elementProperties.get(e),s=this.constructor.p(e,r);if(s!==void 0&&r.reflect===!0){let n=(r.converter?.toAttribute!==void 0?r.converter:Oe).toAttribute(t,r.type);this.m=e,n==null?this.removeAttribute(s):this.setAttribute(s,n),this.m=null}}_$AK(e,t){let r=this.constructor,s=r.u.get(e);if(s!==void 0&&this.m!==s){let n=r.getPropertyOptions(s),o=typeof n.converter=="function"?{fromAttribute:n.converter}:n.converter?.fromAttribute!==void 0?n.converter:Oe;this.m=s;let a=o.fromAttribute(t,n.type);this[s]=a??this.T?.get(s)??a,this.m=null}}requestUpdate(e,t,r){if(e!==void 0){let s=this.constructor,n=this[e];if(r??(r=s.getPropertyOptions(e)),!((r.hasChanged??pt)(n,t)||r.useDefault&&r.reflect&&n===this.T?.get(e)&&!this.hasAttribute(s.p(e,r))))return;this.M(e,t,r)}this.isUpdatePending===!1&&(this.S=this.k())}M(e,t,{useDefault:r,reflect:s,wrapped:n},o){r&&!(this.T??(this.T=new Map)).has(e)&&(this.T.set(e,o??t??this[e]),n!==!0||o!==void 0)||(this._$AL.has(e)||(this.hasUpdated||r||(t=void 0),this._$AL.set(e,t)),s===!0&&this.m!==e&&(this.A??(this.A=new Set)).add(e))}async k(){this.isUpdatePending=!0;try{await this.S}catch(t){Promise.reject(t)}let e=this.scheduleUpdate();return e!=null&&await e,!this.isUpdatePending}scheduleUpdate(){return this.performUpdate()}performUpdate(){if(!this.isUpdatePending)return;if(!this.hasUpdated){if(this.renderRoot??(this.renderRoot=this.createRenderRoot()),this.v){for(let[s,n]of this.v)this[s]=n;this.v=void 0}let r=this.constructor.elementProperties;if(r.size>0)for(let[s,n]of r){let{wrapped:o}=n,a=this[s];o!==!0||this._$AL.has(s)||a===void 0||this.M(s,void 0,n,a)}}let e=!1,t=this._$AL;try{e=this.shouldUpdate(t),e?(this.willUpdate(t),this.P?.forEach(r=>r.hostUpdate?.()),this.update(t)):this.U()}catch(r){throw e=!1,this.U(),r}e&&this._$AE(t)}willUpdate(e){}_$AE(e){this.P?.forEach(t=>t.hostUpdated?.()),this.hasUpdated||(this.hasUpdated=!0,this.firstUpdated(e)),this.updated(e)}U(){this._$AL=new Map,this.isUpdatePending=!1}get updateComplete(){return this.getUpdateComplete()}getUpdateComplete(){return this.S}shouldUpdate(e){return!0}update(e){this.A&&(this.A=this.A.forEach(t=>this.C(t,this[t]))),this.U()}updated(e){}firstUpdated(e){}};E.elementStyles=[],E.shadowRootOptions={mode:"open"},E[J("elementProperties")]=new Map,E[J("finalized")]=new Map,zt?.({ReactiveElement:E}),(V.reactiveElementVersions??(V.reactiveElementVersions=[])).push("2.1.1");var X=globalThis,ue=X.trustedTypes,Qe=ue?ue.createPolicy("lit-html",{createHTML:i=>i}):void 0,He="$lit$",k=`lit$${Math.random().toFixed(9).slice(2)}$`,De="?"+k,qt=`<${De}>`,M=document,ee=()=>M.createComment(""),te=i=>i===null||typeof i!="object"&&typeof i!="function",Ke=Array.isArray,ht=i=>Ke(i)||typeof i?.[Symbol.iterator]=="function",Re=`[ 	
\f\r]`,Y=/<(?:(!--|\/[^a-zA-Z])|(\/?[a-zA-Z][^>\s]*)|(\/?$))/g,Ze=/-->/g,et=/>/g,O=RegExp(`>|${Re}(?:([^\\s"'>=/]+)(${Re}*=${Re}*(?:[^ 	
\f\r"'\`<>=]|("|')|))|$)`,"g"),tt=/'/g,rt=/"/g,ft=/^(?:script|style|textarea|title)$/i,Ue=i=>(e,...t)=>({_$litType$:i,strings:e,values:t}),d=Ue(1),Bt=Ue(2),yr=Ue(3),b=Symbol.for("lit-noChange"),m=Symbol.for("lit-nothing"),st=new WeakMap,N=M.createTreeWalker(M,129);function mt(i,e){if(!Ke(i)||!i.hasOwnProperty("raw"))throw Error("invalid template strings array");return Qe!==void 0?Qe.createHTML(e):e}var gt=(i,e)=>{let t=i.length-1,r=[],s,n=e===2?"<svg>":e===3?"<math>":"",o=Y;for(let a=0;a<t;a++){let l=i[a],c,p,u=-1,f=0;for(;f<l.length&&(o.lastIndex=f,p=o.exec(l),p!==null);)f=o.lastIndex,o===Y?p[1]==="!--"?o=Ze:p[1]!==void 0?o=et:p[2]!==void 0?(ft.test(p[2])&&(s=RegExp("</"+p[2],"g")),o=O):p[3]!==void 0&&(o=O):o===O?p[0]===">"?(o=s??Y,u=-1):p[1]===void 0?u=-2:(u=o.lastIndex-p[2].length,c=p[1],o=p[3]===void 0?O:p[3]==='"'?rt:tt):o===rt||o===tt?o=O:o===Ze||o===et?o=Y:(o=O,s=void 0);let h=o===O&&i[a+1].startsWith("/>")?" ":"";n+=o===Y?l+qt:u>=0?(r.push(c),l.slice(0,u)+He+l.slice(u)+k+h):l+k+(u===-2?a:h)}return[mt(i,n+(i[t]||"<?>")+(e===2?"</svg>":e===3?"</math>":"")),r]},re=class i{constructor({strings:e,_$litType$:t},r){let s;this.parts=[];let n=0,o=0,a=e.length-1,l=this.parts,[c,p]=gt(e,t);if(this.el=i.createElement(c,r),N.currentNode=this.el.content,t===2||t===3){let u=this.el.content.firstChild;u.replaceWith(...u.childNodes)}for(;(s=N.nextNode())!==null&&l.length<a;){if(s.nodeType===1){if(s.hasAttributes())for(let u of s.getAttributeNames())if(u.endsWith(He)){let f=p[o++],h=s.getAttribute(u).split(k),y=/([.?@])?(.*)/.exec(f);l.push({type:1,index:n,name:y[2],strings:h,ctor:y[1]==="."?he:y[1]==="?"?fe:y[1]==="@"?me:L}),s.removeAttribute(u)}else u.startsWith(k)&&(l.push({type:6,index:n}),s.removeAttribute(u));if(ft.test(s.tagName)){let u=s.textContent.split(k),f=u.length-1;if(f>0){s.textContent=ue?ue.emptyScript:"";for(let h=0;h<f;h++)s.append(u[h],ee()),N.nextNode(),l.push({type:2,index:++n});s.append(u[f],ee())}}}else if(s.nodeType===8)if(s.data===De)l.push({type:2,index:n});else{let u=-1;for(;(u=s.data.indexOf(k,u+1))!==-1;)l.push({type:7,index:n}),u+=k.length-1}n++}}static createElement(e,t){let r=M.createElement("template");return r.innerHTML=e,r}};function P(i,e,t=i,r){if(e===b)return e;let s=r!==void 0?t.N?.[r]:t.O,n=te(e)?void 0:e._$litDirective$;return s?.constructor!==n&&(s?._$AO?.(!1),n===void 0?s=void 0:(s=new n(i),s._$AT(i,t,r)),r!==void 0?(t.N??(t.N=[]))[r]=s:t.O=s),s!==void 0&&(e=P(i,s._$AS(i,e.values),s,r)),e}var pe=class{constructor(e,t){this._$AV=[],this._$AN=void 0,this._$AD=e,this._$AM=t}get parentNode(){return this._$AM.parentNode}get _$AU(){return this._$AM._$AU}R(e){let{el:{content:t},parts:r}=this._$AD,s=(e?.creationScope??M).importNode(t,!0);N.currentNode=s;let n=N.nextNode(),o=0,a=0,l=r[0];for(;l!==void 0;){if(o===l.index){let c;l.type===2?c=new xe(n,n.nextSibling,this,e):l.type===1?c=new l.ctor(n,l.name,l.strings,this,e):l.type===6&&(c=new ge(n,this,e)),this._$AV.push(c),l=r[++a]}o!==l?.index&&(n=N.nextNode(),o++)}return N.currentNode=M,s}V(e){let t=0;for(let r of this._$AV)r!==void 0&&(r.strings!==void 0?(r._$AI(e,r,t),t+=r.strings.length-2):r._$AI(e[t])),t++}},xe=class yt{get _$AU(){return this._$AM?._$AU??this.D}constructor(e,t,r,s){this.type=2,this._$AH=m,this._$AN=void 0,this._$AA=e,this._$AB=t,this._$AM=r,this.options=s,this.D=s?.isConnected??!0}get parentNode(){let e=this._$AA.parentNode,t=this._$AM;return t!==void 0&&e?.nodeType===11&&(e=t.parentNode),e}get startNode(){return this._$AA}get endNode(){return this._$AB}_$AI(e,t=this){e=P(this,e,t),te(e)?e===m||e==null||e===""?(this._$AH!==m&&this._$AR(),this._$AH=m):e!==this._$AH&&e!==b&&this.L(e):e._$litType$!==void 0?this.j(e):e.nodeType!==void 0?this.I(e):ht(e)?this.H(e):this.L(e)}B(e){return this._$AA.parentNode.insertBefore(e,this._$AB)}I(e){this._$AH!==e&&(this._$AR(),this._$AH=this.B(e))}L(e){this._$AH!==m&&te(this._$AH)?this._$AA.nextSibling.data=e:this.I(M.createTextNode(e)),this._$AH=e}j(e){let{values:t,_$litType$:r}=e,s=typeof r=="number"?this._$AC(e):(r.el===void 0&&(r.el=re.createElement(mt(r.h,r.h[0]),this.options)),r);if(this._$AH?._$AD===s)this._$AH.V(t);else{let n=new pe(s,this),o=n.R(this.options);n.V(t),this.I(o),this._$AH=n}}_$AC(e){let t=st.get(e.strings);return t===void 0&&st.set(e.strings,t=new re(e)),t}H(e){Ke(this._$AH)||(this._$AH=[],this._$AR());let t=this._$AH,r,s=0;for(let n of e)s===t.length?t.push(r=new yt(this.B(ee()),this.B(ee()),this,this.options)):r=t[s],r._$AI(n),s++;s<t.length&&(this._$AR(r&&r._$AB.nextSibling,s),t.length=s)}_$AR(e=this._$AA.nextSibling,t){for(this._$AP?.(!1,!0,t);e!==this._$AB;){let r=e.nextSibling;e.remove(),e=r}}setConnected(e){this._$AM===void 0&&(this.D=e,this._$AP?.(e))}},L=class{get tagName(){return this.element.tagName}get _$AU(){return this._$AM._$AU}constructor(e,t,r,s,n){this.type=1,this._$AH=m,this._$AN=void 0,this.element=e,this.name=t,this._$AM=s,this.options=n,r.length>2||r[0]!==""||r[1]!==""?(this._$AH=Array(r.length-1).fill(new String),this.strings=r):this._$AH=m}_$AI(e,t=this,r,s){let n=this.strings,o=!1;if(n===void 0)e=P(this,e,t,0),o=!te(e)||e!==this._$AH&&e!==b,o&&(this._$AH=e);else{let a=e,l,c;for(e=n[0],l=0;l<n.length-1;l++)c=P(this,a[r+l],t,l),c===b&&(c=this._$AH[l]),o||(o=!te(c)||c!==this._$AH[l]),c===m?e=m:e!==m&&(e+=(c??"")+n[l+1]),this._$AH[l]=c}o&&!s&&this.W(e)}W(e){e===m?this.element.removeAttribute(this.name):this.element.setAttribute(this.name,e??"")}},he=class extends L{constructor(){super(...arguments),this.type=3}W(e){this.element[this.name]=e===m?void 0:e}},fe=class extends L{constructor(){super(...arguments),this.type=4}W(e){this.element.toggleAttribute(this.name,!!e&&e!==m)}},me=class extends L{constructor(e,t,r,s,n){super(e,t,r,s,n),this.type=5}_$AI(e,t=this){if((e=P(this,e,t,0)??m)===b)return;let r=this._$AH,s=e===m&&r!==m||e.capture!==r.capture||e.once!==r.once||e.passive!==r.passive,n=e!==m&&(r===m||s);s&&this.element.removeEventListener(this.name,this,r),n&&this.element.addEventListener(this.name,this,e),this._$AH=e}handleEvent(e){typeof this._$AH=="function"?this._$AH.call(this.options?.host??this.element,e):this._$AH.handleEvent(e)}},ge=class{constructor(e,t,r){this.element=e,this.type=6,this._$AN=void 0,this._$AM=t,this.options=r}get _$AU(){return this._$AM._$AU}_$AI(e){P(this,e)}},Wt={q:He,J:k,Z:De,F:1,G:gt,K:pe,X:ht,Y:P,tt:xe,st:L,it:fe,et:me,ht:he,ot:ge},Gt=X.litHtmlPolyfillSupport;Gt?.(re,xe),(X.litHtmlVersions??(X.litHtmlVersions=[])).push("3.3.1");var bt=(i,e,t)=>{let r=t?.renderBefore??e,s=r._$litPart$;if(s===void 0){let n=t?.renderBefore??null;r._$litPart$=s=new xe(e.insertBefore(ee(),n),n,void 0,t??{})}return s._$AI(i),s},Q=globalThis;var _=class extends E{constructor(){super(...arguments),this.renderOptions={host:this},this.rt=void 0}createRenderRoot(){var t;let e=super.createRenderRoot();return(t=this.renderOptions).renderBefore??(t.renderBefore=e.firstChild),e}update(e){let t=this.render();this.hasUpdated||(this.renderOptions.isConnected=this.isConnected),super.update(e),this.rt=bt(t,this.renderRoot,this.renderOptions)}connectedCallback(){super.connectedCallback(),this.rt?.setConnected(!0)}disconnectedCallback(){super.disconnectedCallback(),this.rt?.setConnected(!1)}render(){return b}};_._$litElement$=!0,_.finalized=!0,Q.litElementHydrateSupport?.({LitElement:_});var Yt=Q.litElementPolyfillSupport;Yt?.({LitElement:_});(Q.litElementVersions??(Q.litElementVersions=[])).push("4.2.1");var{tt:Jt}=Wt,Xt=i=>i===null||typeof i!="object"&&typeof i!="function";var it=(i,e)=>e===void 0?i?._$litType$!==void 0:i?._$litType$===e,Qt=i=>i?._$litType$?.h!=null;var vt=i=>i.strings===void 0,nt=()=>document.createComment(""),C=(i,e,t)=>{let r=i._$AA.parentNode,s=e===void 0?i._$AB:e._$AA;if(t===void 0){let n=r.insertBefore(nt(),s),o=r.insertBefore(nt(),s);t=new Jt(n,o,i,i.options)}else{let n=t._$AB.nextSibling,o=t._$AM,a=o!==i;if(a){let l;t._$AQ?.(i),t._$AM=i,t._$AP!==void 0&&(l=i._$AU)!==o._$AU&&t._$AP(l)}if(n!==s||a){let l=t._$AA;for(;l!==n;){let c=l.nextSibling;r.insertBefore(l,s),l=c}}}return t},S=(i,e,t=i)=>(i._$AI(e,t),i),Zt={},se=(i,e=Zt)=>i._$AH=e,Ne=i=>i._$AH,Te=i=>{i._$AR(),i._$AA.remove()},$t=i=>{i._$AR()};var $=i=>(...e)=>({_$litDirective$:i,values:e}),w=class{constructor(e){}get _$AU(){return this._$AM._$AU}_$AT(e,t,r){this.nt=e,this._$AM=t,this.ct=r}_$AS(e,t){return this.update(e,t)}update(e,t){return this.render(...t)}};var Z=(i,e)=>{let t=i._$AN;if(t===void 0)return!1;for(let r of t)r._$AO?.(e,!1),Z(r,e);return!0},ye=i=>{let e,t;do{if((e=i._$AM)===void 0)break;t=e._$AN,t.delete(i),i=e}while(t?.size===0)},xt=i=>{for(let e;e=i._$AM;i=e){let t=e._$AN;if(t===void 0)e._$AN=t=new Set;else if(t.has(i))break;t.add(i),rr(e)}};function er(i){this._$AN!==void 0?(ye(this),this._$AM=i,xt(this)):this._$AM=i}function tr(i,e=!1,t=0){let r=this._$AH,s=this._$AN;if(s!==void 0&&s.size!==0)if(e)if(Array.isArray(r))for(let n=t;n<r.length;n++)Z(r[n],!1),ye(r[n]);else r!=null&&(Z(r,!1),ye(r));else Z(this,i)}var rr=i=>{i.type==2&&(i._$AP??(i._$AP=tr),i._$AQ??(i._$AQ=er))},ie=class extends w{constructor(){super(...arguments),this._$AN=void 0}_$AT(e,t,r){super._$AT(e,t,r),xt(this),this.isConnected=e._$AU}_$AO(e,t=!0){e!==this.isConnected&&(this.isConnected=e,e?this.reconnected?.():this.disconnected?.()),t&&(Z(this,e),ye(this))}setValue(e){if(vt(this.nt))this.nt._$AI(e,this);else{let t=[...this.nt._$AH];t[this.ct]=e,this.nt._$AI(t,this,0)}}disconnected(){}reconnected(){}};var be=class{constructor(e){this.lt=e}disconnect(){this.lt=void 0}reconnect(e){this.lt=e}deref(){return this.lt}},ve=class{constructor(){this.ut=void 0,this.dt=void 0}get(){return this.ut}pause(){this.ut??(this.ut=new Promise(e=>this.dt=e))}resume(){this.dt?.(),this.ut=this.dt=void 0}};var $e=class extends ie{constructor(){super(...arguments),this.ft=new be(this),this.vt=new ve}render(e,t){return b}update(e,[t,r]){if(this.isConnected||this.disconnected(),t===this.yt)return b;this.yt=t;let s=0,{ft:n,vt:o}=this;return(async(a,l)=>{for await(let c of a)if(await l(c)===!1)return})(t,async a=>{for(;o.get();)await o.get();let l=n.deref();if(l!==void 0){if(l.yt!==t)return!1;r!==void 0&&(a=r(a,s)),l.commitValue(a,s),s++}return!0}),b}commitValue(e,t){this.setValue(e)}disconnected(){this.ft.disconnect(),this.vt.pause()}reconnected(){this.ft.reconnect(this),this.vt.resume()}},br=$($e),vr=$(class extends $e{constructor(i){if(super(i),i.type!==2)throw Error("asyncAppend can only be used in child expressions")}update(i,e){return this.rt=i,super.update(i,e)}commitValue(i,e){e===0&&$t(this.rt);let t=C(this.rt);S(t,i)}}),ot=i=>Qt(i)?i._$litType$.h:i.strings,$r=$(class extends w{constructor(i){super(i),this.bt=new WeakMap}render(i){return[i]}update(i,[e]){let t=it(this.gt)?ot(this.gt):null,r=it(e)?ot(e):null;if(t!==null&&(r===null||t!==r)){let s=Ne(i).pop(),n=this.bt.get(t);if(n===void 0){let o=document.createDocumentFragment();n=bt(m,o),n.setConnected(!1),this.bt.set(t,n)}se(n,[s]),C(n,void 0,s)}if(r!==null){if(t===null||t!==r){let s=this.bt.get(r);if(s!==void 0){let n=Ne(s).pop();$t(i),C(i,void 0,n),se(i,[n])}}this.gt=e}else this.gt=void 0;return this.render(e)}});var xr=$(class extends w{constructor(i){if(super(i),i.type!==1||i.name!=="class"||i.strings?.length>2)throw Error("`classMap()` can only be used in the `class` attribute and must be the only part in the attribute.")}render(i){return" "+Object.keys(i).filter(e=>i[e]).join(" ")+" "}update(i,[e]){if(this.wt===void 0){this.wt=new Set,i.strings!==void 0&&(this._t=new Set(i.strings.join(" ").split(/\s/).filter(r=>r!=="")));for(let r in e)e[r]&&!this._t?.has(r)&&this.wt.add(r);return this.render(e)}let t=i.element.classList;for(let r of this.wt)r in e||(t.remove(r),this.wt.delete(r));for(let r in e){let s=!!e[r];s===this.wt.has(r)||this._t?.has(r)||(s?(t.add(r),this.wt.add(r)):(t.remove(r),this.wt.delete(r)))}return b}}),sr={},_r=$(class extends w{constructor(){super(...arguments),this.St=sr}render(i,e){return e()}update(i,[e,t]){if(Array.isArray(e)){if(Array.isArray(this.St)&&this.St.length===e.length&&e.every((r,s)=>r===this.St[s]))return b}else if(this.St===e)return b;return this.St=Array.isArray(e)?Array.from(e):e,this.render(e,t)}}),_e=i=>i??m;var wr=$(class extends w{constructor(){super(...arguments),this.key=m}render(i,e){return this.key=i,e}update(i,[e,t]){return e!==this.key&&(se(i),this.key=e),t}}),Ar=$(class extends w{constructor(i){if(super(i),i.type!==3&&i.type!==1&&i.type!==4)throw Error("The `live` directive is not allowed on child or event bindings");if(!vt(i))throw Error("`live` bindings can only contain a single expression")}render(i){return i}update(i,[e]){if(e===b||e===m)return e;let t=i.element,r=i.name;if(i.type===3){if(e===t[r])return b}else if(i.type===4){if(!!e===t.hasAttribute(r))return b}else if(i.type===1&&t.getAttribute(r)===e+"")return b;return se(i),e}});var Fe=new WeakMap,kr=$(class extends ie{render(i){return m}update(i,[e]){let t=e!==this.lt;return t&&this.lt!==void 0&&this.$t(void 0),(t||this.Tt!==this.xt)&&(this.lt=e,this.Et=i.options?.host,this.$t(this.xt=i.element)),m}$t(i){if(this.isConnected||(i=void 0),typeof this.lt=="function"){let e=this.Et??globalThis,t=Fe.get(e);t===void 0&&(t=new WeakMap,Fe.set(e,t)),t.get(this.lt)!==void 0&&this.lt.call(this.Et,void 0),t.set(this.lt,i),i!==void 0&&this.lt.call(this.Et,i)}else this.lt.value=i}get Tt(){return typeof this.lt=="function"?Fe.get(this.Et??globalThis)?.get(this.lt):this.lt?.value}disconnected(){this.Tt===this.xt&&this.$t(void 0)}reconnected(){this.$t(this.xt)}}),at=(i,e,t)=>{let r=new Map;for(let s=e;s<=t;s++)r.set(i[s],s);return r},Sr=$(class extends w{constructor(i){if(super(i),i.type!==2)throw Error("repeat() can only be used in text expressions")}Ct(i,e,t){let r;t===void 0?t=e:e!==void 0&&(r=e);let s=[],n=[],o=0;for(let a of i)s[o]=r?r(a,o):o,n[o]=t(a,o),o++;return{values:n,keys:s}}render(i,e,t){return this.Ct(i,e,t).values}update(i,[e,t,r]){let s=Ne(i),{values:n,keys:o}=this.Ct(e,t,r);if(!Array.isArray(s))return this.Pt=o,n;let a=this.Pt??(this.Pt=[]),l=[],c,p,u=0,f=s.length-1,h=0,y=n.length-1;for(;u<=f&&h<=y;)if(s[u]===null)u++;else if(s[f]===null)f--;else if(a[u]===o[h])l[h]=S(s[u],n[h]),u++,h++;else if(a[f]===o[y])l[y]=S(s[f],n[y]),f--,y--;else if(a[u]===o[y])l[y]=S(s[u],n[y]),C(i,l[y+1],s[u]),u++,y--;else if(a[f]===o[h])l[h]=S(s[f],n[h]),C(i,s[u],s[f]),f--,h++;else if(c===void 0&&(c=at(o,h,y),p=at(a,u,f)),c.has(a[u]))if(c.has(a[f])){let A=p.get(o[h]),Ve=A!==void 0?s[A]:null;if(Ve===null){let Be=C(i,s[u]);S(Be,n[h]),l[h]=Be}else l[h]=S(Ve,n[h]),C(i,s[u],Ve),s[A]=null;h++}else Te(s[f]),f--;else Te(s[u]),u++;for(;h<=y;){let A=C(i,l[y+1]);S(A,n[h]),l[h++]=A}for(;u<=f;){let A=s[u++];A!==null&&Te(A)}return this.Pt=o,se(i,l),b}}),_t="important",ir=" !"+_t,Er=$(class extends w{constructor(i){if(super(i),i.type!==1||i.name!=="style"||i.strings?.length>2)throw Error("The `styleMap` directive must be used in the `style` attribute and must be the only part in the attribute.")}render(i){return Object.keys(i).reduce((e,t)=>{let r=i[t];return r==null?e:e+`${t=t.includes("-")?t:t.replace(/(?:^(webkit|moz|ms|o)|)(?=[A-Z])/g,"-$&").toLowerCase()}:${r};`},"")}update(i,[e]){let{style:t}=i.element;if(this.Mt===void 0)return this.Mt=new Set(Object.keys(e)),this.render(e);for(let r of this.Mt)e[r]==null&&(this.Mt.delete(r),r.includes("-")?t.removeProperty(r):t[r]=null);for(let r in e){let s=e[r];if(s!=null){this.Mt.add(r);let n=typeof s=="string"&&s.endsWith(ir);r.includes("-")||n?t.setProperty(r,n?s.slice(0,-11):s,n?_t:""):t[r]=s}}return b}}),Cr=$(class extends w{constructor(i){if(super(i),i.type!==2)throw Error("templateContent can only be used in child bindings")}render(i){return this.At===i?b:(this.At=i,document.importNode(i.content,!0))}}),H=class extends w{constructor(e){if(super(e),this.gt=m,e.type!==2)throw Error(this.constructor.directiveName+"() can only be used in child bindings")}render(e){if(e===m||e==null)return this.kt=void 0,this.gt=e;if(e===b)return e;if(typeof e!="string")throw Error(this.constructor.directiveName+"() called with a non-string value");if(e===this.gt)return this.kt;this.gt=e;let t=[e];return t.raw=t,this.kt={_$litType$:this.constructor.resultType,strings:t,values:[]}}};H.directiveName="unsafeHTML",H.resultType=1;var Vr=$(H);var ne=class extends H{};ne.directiveName="unsafeSVG",ne.resultType=2;var Rr=$(ne),lt=i=>!Xt(i)&&typeof i.then=="function",dt=1073741823;var Me=class extends ie{constructor(){super(...arguments),this.Ot=dt,this.Ut=[],this.ft=new be(this),this.vt=new ve}render(...e){return e.find(t=>!lt(t))??b}update(e,t){let r=this.Ut,s=r.length;this.Ut=t;let n=this.ft,o=this.vt;this.isConnected||this.disconnected();for(let a=0;a<t.length&&!(a>this.Ot);a++){let l=t[a];if(!lt(l))return this.Ot=a,l;a<s&&l===r[a]||(this.Ot=dt,s=0,Promise.resolve(l).then(async c=>{for(;o.get();)await o.get();let p=n.deref();if(p!==void 0){let u=p.Ut.indexOf(l);u>-1&&u<p.Ot&&(p.Ot=u,p.setValue(c))}}))}return b}disconnected(){this.ft.disconnect(),this.vt.pause()}reconnected(){this.ft.reconnect(this),this.vt.resume()}},Tr=$(Me);var nr=Symbol.for(""),or=i=>{if(i?.r===nr)return i?._$litStatic$};var ct=new Map,wt=i=>(e,...t)=>{let r=t.length,s,n,o=[],a=[],l,c=0,p=!1;for(;c<r;){for(l=e[c];c<r&&(n=t[c],(s=or(n))!==void 0);)l+=s+e[++c],p=!0;c!==r&&a.push(n),o.push(l),c++}if(c===r&&o.push(e[r]),p){let u=o.join("$$lit$$");(e=ct.get(u))===void 0&&(o.raw=o,ct.set(u,e=o)),t=a}return i(e,...t)},Fr=wt(d),Or=wt(Bt);window.litDisableBundleWarning||console.warn("Lit has been loaded from a bundle that combines all core features into a single file. To reduce transfer size and parsing cost, consider using the `lit` npm package directly in your project.");function we(i){if(i==null)return"";let e=document.createElement("div");return e.textContent=String(i),e.innerHTML}function At(i){return i?["calibration","baudrate","data_bits","stop_bits","port","timeout"].some(t=>i.toLowerCase().includes(t)):!1}function kt(i){return i?["enabled","enable","active","disabled"].some(t=>i.toLowerCase().includes(t)):!1}function ar(i,e){let t={},r={};e.forEach(s=>{let n=s.key||s.name;r[n]=s.fieldType||s.type});for(let[s,n]of i.entries())if(n&&n.trim()!==""){let o=r[s]||"text";if(o==="number"||o==="integer"||At(s)){let a=Number(n);t[s]=isNaN(a)?n:a}else o==="boolean"?t[s]=!0:t[s]=n}return e.forEach(s=>{let n=s.key||s.name;(s.type==="boolean"||kt(n))&&(i.has(n)||(t[n]=!1))}),t}var je=class{constructor(){this.renderers=new Map,this.defaultFieldType="text"}register(e){let t=e.fieldType;if(!t)throw new Error(`Renderer must define static fieldType. Got: ${e.name}`);let r=new e;this.renderers.set(t,r),console.debug(`[FieldRegistry] Registered renderer for fieldType: ${t}`)}getRenderer(e){return this.renderers.get(e)||null}hasRenderer(e){return this.renderers.has(e)}getRegisteredTypes(){return Array.from(this.renderers.keys())}render(e,t,r){let s=e.fieldType||e.type||this.defaultFieldType,n=this.getRenderer(s);return n?n.render(e,t,r):(console.warn(`[FieldRegistry] Unknown fieldType: "${s}", falling back to "${this.defaultFieldType}". Field: ${e.key||e.name}`),this.getRenderer(this.defaultFieldType).render(e,t,r))}getValue(e,t){let r=t.fieldType||t.type||this.defaultFieldType,s=this.getRenderer(r);if(!s){let n=t.key||t.name;return e.get(n)}return s.getValue(e,t)}getDefaultValue(e){let t=e.fieldType||e.type||this.defaultFieldType,r=this.getRenderer(t);return r?r.getDefaultValue(e):e.defaultValue}},v=new je;var g=class{static get fieldType(){throw new Error("static fieldType must be implemented by subclass")}render(e,t,r){throw new Error("render() must be implemented by subclass")}getValue(e,t){let r=t.key||t.name;return e.get(r)}getDefaultValue(e){return e.defaultValue}getFieldKey(e){return e.key||e.name}renderLabel(e,t){let r=this.getFieldKey(e);return t`
      <label for="${r}">
        ${e.displayName||r}
        ${e.required?t`<span class="required">*</span>`:""}
      </label>
    `}renderError(e,t){return e?t`<div class="error">${this.escapeHtml(e)}</div>`:null}escapeHtml(e){if(e==null)return"";let t=document.createElement("div");return t.textContent=String(e),t.innerHTML}};var D=class extends g{static get fieldType(){return"text"}render(e,t,r){let s=this.getFieldKey(e),n=t??e.defaultValue??"";return d`
      <div class="form-group">
        ${this.renderLabel(e,d)}
        <input
          type="text"
          id="${s}"
          name="${s}"
          .value=${n}
          placeholder="${e.placeholder||""}"
          minlength="${_e(e.minLength)}"
          maxlength="${_e(e.maxLength)}"
          pattern="${_e(e.pattern)}"
          ?required=${e.required}
          ?readonly=${e.readOnly||e.readonly}
        />
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getDefaultValue(e){return e.defaultValue??""}};var K=class extends g{static get fieldType(){return"numeric"}render(e,t,r){let s=this.getFieldKey(e),n=t??e.defaultValue??"";return d`
      <div class="form-group">
        ${this.renderLabel(e,d)}
        <input
          type="number"
          id="${s}"
          name="${s}"
          .value=${n}
          min="${e.min??""}"
          max="${e.max??""}"
          step="${e.step??"any"}"
          placeholder="${e.placeholder||""}"
          ?required=${e.required}
          ?readonly=${e.readOnly||e.readonly}
        />
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t),s=e.get(r);if(s===null||s==="")return t.defaultValue??null;let n=Number(s);return isNaN(n)?s:n}getDefaultValue(e){let t=e.defaultValue;if(t==null)return null;let r=Number(t);return isNaN(r)?t:r}};var U=class extends g{static get fieldType(){return"float"}render(e,t,r){let s=this.getFieldKey(e),n=t??e.defaultValue??"";return d`
      <div class="form-group">
        ${this.renderLabel(e,d)}
        <input
          type="number"
          id="${s}"
          name="${s}"
          .value=${n}
          min="${e.min??""}"
          max="${e.max??""}"
          step="any"
          placeholder="${e.placeholder||"0.00"}"
          ?required=${e.required}
          ?readonly=${e.readOnly||e.readonly}
        />
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t),s=e.get(r);if(s===null||s==="")return t.defaultValue!==void 0?parseFloat(t.defaultValue):null;let n=parseFloat(s);return isNaN(n)?null:n}getDefaultValue(e){let t=e.defaultValue;if(t==null)return null;let r=parseFloat(t);return isNaN(r)?null:r}};var Ae=-32768,ke=32767,j=class extends g{static get fieldType(){return"short"}render(e,t,r){let s=this.getFieldKey(e),n=t??e.defaultValue??"",o=e.min!=null?Math.max(e.min,Ae):Ae,a=e.max!=null?Math.min(e.max,ke):ke;return d`
      <div class="form-group">
        ${this.renderLabel(e,d)}
        <input
          type="number"
          id="${s}"
          name="${s}"
          .value=${n}
          min="${o}"
          max="${a}"
          step="1"
          placeholder="${e.placeholder||"0"}"
          ?required=${e.required}
          ?readonly=${e.readOnly||e.readonly}
        />
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t),s=e.get(r);if(s===null||s==="")return t.defaultValue!==void 0?this.toShort(t.defaultValue):null;let n=parseInt(s,10);return isNaN(n)?null:Math.max(Ae,Math.min(ke,n))}getDefaultValue(e){let t=e.defaultValue;return t==null?null:this.toShort(t)}toShort(e){let t=parseInt(e,10);return isNaN(t)?null:Math.max(Ae,Math.min(ke,t))}};var I=class extends g{static get fieldType(){return"boolean"}render(e,t,r){let s=this.getFieldKey(e),n=this.parseBoolean(t??e.defaultValue);return d`
      <div class="form-group">
        <div class="checkbox-wrapper">
          <input
            type="checkbox"
            id="${s}"
            name="${s}"
            ?checked=${n}
          />
          <label for="${s}">
            ${e.displayName||s}
            ${e.required?d`<span class="required">*</span>`:""}
          </label>
        </div>
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t);return e.has(r)}getDefaultValue(e){return this.parseBoolean(e.defaultValue)}parseBoolean(e){if(e==null)return!1;if(typeof e=="boolean")return e;if(typeof e=="string"){let t=e.toLowerCase().trim();return t==="true"||t==="1"||t==="yes"||t==="on"}return!!e}};var R=class extends g{static get fieldType(){return"enum"}render(e,t,r){let s=this.getFieldKey(e),n=this.parseOptions(e.options||[]),o=e.displayMapping||{},a=t??e.defaultValue??"",l=a!==""&&a!=null;return d`
      <div class="form-group">
        ${this.renderLabel(e,d)}
        <select
          id="${s}"
          name="${s}"
        >
          <option value="" ?selected=${!l}>${e.placeholder||"\u8BF7\u9009\u62E9..."}</option>
          ${n.map(c=>{let p=typeof c=="object"?c.value:c,u=typeof c=="object"?c.label||c.value:o[c]||c;return d`
              <option
                value="${p}"
                ?selected=${String(a)===String(p)}
              >
                ${this.escapeHtml(u)}
              </option>
            `})}
        </select>
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t);return e.get(r)||""}getDefaultValue(e){return e.defaultValue??""}parseOptions(e){return Array.isArray(e)?e:(console.warn("[EnumFieldRenderer] options is not an array:",e),[])}};var z=class extends g{static get fieldType(){return"array"}render(e,t,r){let s=this.getFieldKey(e),n=this.parseOptions(e.options||[]),o=this.normalizeValue(t??e.defaultValue??[]);return d`
      <div class="form-group">
        ${this.renderLabel(e,d)}
        <div class="checkbox-group" id="${s}" role="group" aria-labelledby="${s}-label">
          ${n.map(a=>{let l=typeof a=="object"?a.value:a,c=typeof a=="object"?a.label||a.value:a,p=o.includes(l);return d`
              <div class="checkbox-wrapper">
                <input
                  type="checkbox"
                  name="${s}"
                  value="${l}"
                  id="${s}_${l}"
                  ?checked=${p}
                  ?disabled=${e.readonly}
                />
                <label for="${s}_${l}">${this.escapeHtml(c)}</label>
              </div>
            `})}
        </div>
        ${e.description&&!r?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:""}
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t);return e.getAll(r).filter(n=>n!=="")}getDefaultValue(e){return this.normalizeValue(e.defaultValue||[])}parseOptions(e){return Array.isArray(e)?e:(console.warn("[ArrayFieldRenderer] options is not an array:",e),[])}normalizeValue(e){if(Array.isArray(e))return e.map(t=>String(t));if(e==null)return[];if(typeof e=="string")try{let t=JSON.parse(e);if(Array.isArray(t))return t.map(r=>String(r))}catch{return e.split(",").map(t=>t.trim()).filter(t=>t)}return[String(e)]}};var lr=`
.multi-select-renderer{display:block}
.ms-trigger{display:flex;align-items:center;gap:4px;width:100%;height:26px;padding:0 6px 0 8px;border:1px solid var(--border,#e0e0e0);border-radius:var(--radius-sm,6px);background:var(--bg,#fff);font-size:13px;cursor:pointer;box-sizing:border-box;user-select:none}
.ms-trigger:hover{border-color:var(--primary,#4f6ef7)}
.ms-trigger:focus{outline:none;border-color:var(--primary,#4f6ef7)}
.ms-trigger.ms-readonly{background:#f3f4f6;color:#6b7280;cursor:default}
.ms-trigger.ms-readonly:hover{border-color:var(--border,#e0e0e0)}
.ms-trigger-text{flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;text-align:left}
.ms-trigger-text.ms-placeholder{color:var(--text-muted,#6b7280)}
.ms-trigger svg{width:14px;height:14px;flex:none;color:var(--text-muted,#6b7280)}
.ms-panel{display:none;position:fixed;background:var(--bg,#fff);border:1px solid var(--border,#e0e0e0);border-radius:var(--radius-sm,6px);box-shadow:0 6px 20px rgba(0,0,0,.13);z-index:3000;min-width:200px}
.ms-panel.ms-open{display:block}
.ms-options{max-height:240px;overflow-y:auto;padding:6px 10px}
.ms-option{display:flex;align-items:center;gap:8px;padding:4px 2px;cursor:pointer}
.ms-option input[type="checkbox"]{width:16px;height:16px;margin:0;flex:none;cursor:pointer}
.ms-option label{font-weight:normal;margin:0;cursor:pointer;font-size:13px;color:var(--text,#1f2937)}
.ms-footer{display:flex;justify-content:flex-end;gap:8px;padding:8px 10px;border-top:1px solid var(--border,#e0e0e0)}
.ms-btn{padding:4px 12px;font-size:12px;font-weight:500;border-radius:var(--radius-sm,6px);cursor:pointer;border:1px solid transparent}
.ms-btn-primary{background:var(--primary,#4f6ef7);color:#fff}
.ms-btn-primary:hover{filter:brightness(1.08)}
.ms-btn-cancel{background:var(--bg,#fff);border-color:var(--border,#e0e0e0);color:var(--text,#1f2937)}
.ms-btn-cancel:hover{background:var(--secondary-light,#f1f5f9)}
`,dr=d`<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"/></svg>`,T=null;function ze(i){if(Array.isArray(i))return i.map(e=>String(e));if(i==null)return[];if(typeof i=="string")try{let e=JSON.parse(i);if(Array.isArray(e))return e.map(t=>String(t))}catch{return i.split(",").map(e=>e.trim()).filter(e=>e)}return[String(i)]}function Ie(i){if(i==null)return"";let e=document.createElement("div");return e.textContent=String(i),e.innerHTML}var Se=class extends _{createRenderRoot(){return this}constructor(){super(),this._selected=[],this._open=!1,this._snapshot=null,this._lastFieldKey=null,this._onDocMouseDown=e=>{e.composedPath().includes(this)||this._cancel()},this._onDocKeydown=e=>{e.key==="Escape"&&this._cancel()},this._onViewportScroll=e=>{e.target&&e.target.nodeType===1&&this.contains(e.target)||this._cancel()},this._onViewportResize=()=>this._cancel()}connectedCallback(){super.connectedCallback();let e=this.getRootNode();if(e&&e.getElementById&&!e.getElementById("ecat-multi-select-styles")){let t=document.createElement("style");t.id="ecat-multi-select-styles",t.textContent=lr,(e.head||e).appendChild(t)}}disconnectedCallback(){T===this&&this._close(),super.disconnectedCallback()}willUpdate(e){if(e.has("field")||e.has("value")){let t=this._fieldKey();this._lastFieldKey!==null&&this._lastFieldKey!==t&&T===this&&this._close(),this._lastFieldKey=t,this._selected=ze(this.value??(this.field&&this.field.defaultValue)??[])}}_fieldKey(){return this.field&&(this.field.key||this.field.name)||""}_readOnly(){return!!(this.field&&(this.field.readOnly||this.field.readonly))}_options(){let e=this.field&&this.field.options||[];return Array.isArray(e)?e.map(t=>{let r=typeof t=="object"?t.value:t,s=typeof t=="object"?t.label||t.value:t;return{value:String(r),label:String(s)}}):(console.warn("[MultiSelectFieldRenderer] options is not an array:",e),[])}_checkedValues(){return Array.from(this.querySelectorAll('.ms-panel input[type="checkbox"]:checked')).map(e=>e.value)}_onTriggerClick(){this._readOnly()||(this._open?this._cancel():this._openPanel())}_onTriggerKeydown(e){this._readOnly()||(e.key==="Enter"||e.key===" ")&&(e.preventDefault(),this._open?this._cancel():this._openPanel())}async _openPanel(){T&&T!==this&&T._cancel(),T=this,this._snapshot=this._checkedValues(),this._open=!0,document.addEventListener("mousedown",this._onDocMouseDown),document.addEventListener("keydown",this._onDocKeydown),window.addEventListener("scroll",this._onViewportScroll,!0),window.addEventListener("resize",this._onViewportResize),await this.updateComplete,this._positionPanel()}_positionPanel(){let e=this.querySelector(".ms-trigger"),t=this.querySelector(".ms-panel");if(!e||!t)return;let r=e.getBoundingClientRect(),s=t.offsetWidth,n=t.offsetHeight,o=window.innerHeight,a=window.innerWidth,l=r.left;l+s>a-8&&(l=Math.max(8,a-8-s));let c;r.bottom+4+n<=o-8||r.bottom+4<=o/2?c=r.bottom+4:c=Math.max(8,r.top-4-n),t.style.left=`${l}px`,t.style.top=`${c}px`,t.style.minWidth=`${Math.max(r.width,200)}px`}_save(){this._selected=this._checkedValues(),this._close(),this.dispatchEvent(new CustomEvent("change",{bubbles:!0,composed:!0}))}_cancel(){this._open&&(this.querySelectorAll('.ms-panel input[type="checkbox"]').forEach(e=>{e.checked=this._snapshot.includes(e.value)}),this._close())}_close(){this._open=!1,this._snapshot=null,T===this&&(T=null),document.removeEventListener("mousedown",this._onDocMouseDown),document.removeEventListener("keydown",this._onDocKeydown),window.removeEventListener("scroll",this._onViewportScroll,!0),window.removeEventListener("resize",this._onViewportResize)}render(){let e=this._fieldKey(),t=this._options(),r=this._readOnly(),s=new Map(t.map(o=>[o.value,o.label])),n=this._selected.map(o=>s.get(o)||o).join("/");return d`
      <div class="form-group">
        <label for="${e}">${this.field&&this.field.displayName||e}${this.field&&this.field.required?d`<span class="required">*</span>`:""}</label>
        <div
          class="ms-trigger${r?" ms-readonly":""}"
          id="${e}"
          role="combobox"
          aria-haspopup="true"
          aria-expanded="${this._open}"
          title="${n}"
          tabindex="${r?-1:0}"
          @click=${this._onTriggerClick}
          @keydown=${this._onTriggerKeydown}
        >
          <span class="ms-trigger-text${n?"":" ms-placeholder"}">${n||"\u672A\u9009\u62E9"}</span>
          ${dr}
        </div>
        <!-- 浮层内勾选是草稿：stopPropagation 拦下 checkbox 原生 change 冒泡，否则 table-field
             _onCellChange 会立即聚合草稿写 _rows（取消后回显文案仍是草稿值）。保存时由本元素
             显式 dispatch 的 change（源=本元素，不在浮层内）冒泡到 td 完成聚合。 -->
        <div class="ms-panel${this._open?" ms-open":""}" @change=${o=>o.stopPropagation()}>
          ${r?this._selected.map(o=>d`<input type="hidden" name="${e}" value="${o}" />`):""}
          <div class="ms-options">
            ${t.map(o=>d`
              <div class="ms-option">
                <input
                  type="checkbox"
                  name="${e}"
                  value="${o.value}"
                  id="${e}_${o.value}"
                  ?checked=${this._selected.includes(o.value)}
                  ?disabled=${r}
                />
                <label for="${e}_${o.value}">${Ie(o.label)}</label>
              </div>
            `)}
          </div>
          ${r?"":d`
            <div class="ms-footer">
              <button type="button" class="ms-btn ms-btn-cancel" @click=${()=>this._cancel()}>取消</button>
              <button type="button" class="ms-btn ms-btn-primary" @click=${()=>this._save()}>保存</button>
            </div>
          `}
        </div>
        ${this.field&&this.field.description&&!this.error?d`<div class="hint">${Ie(this.field.description)}</div>`:""}
        ${this.error?d`<div class="error">${Ie(this.error)}</div>`:""}
      </div>
    `}};F(Se,"properties",{field:{type:Object},value:{type:Object},error:{type:Object},_selected:{state:!0},_open:{state:!0}});customElements.define("multi-select-renderer",Se);var oe=class extends g{static get fieldType(){return"multi_select"}render(e,t,r){return d`<multi-select-renderer .field=${e} .value=${t} .error=${r}></multi-select-renderer>`}getValue(e,t){let r=this.getFieldKey(t);return e.getAll(r).filter(n=>n!=="")}getDefaultValue(e){return ze(e.defaultValue||[])}normalizeValue(e){return ze(e)}parseOptions(e){return Array.isArray(e)?e:(console.warn("[MultiSelectFieldRenderer] options is not an array:",e),[])}};var ae=null;function St(i){ae=i}var q=class extends g{static get fieldType(){return"schema"}render(e,t,r){let s=this.getFieldKey(e);if(e.extendFields&&Array.isArray(e.extendFields))return d``;let n=e.nestedFields||[],o=t||{},a=r&&typeof r=="object"&&!Array.isArray(r);return d`
      <div class="form-group nested-group" data-field-key="${s}">
        <fieldset class="nested-fieldset">
          <legend>${this.escapeHtml(e.displayName||s)}${e.required?d`<span class="required">*</span>`:""}</legend>
          <div class="nested-fields">
            ${n.map(l=>{let c=o[l.key]??l.defaultValue??"",p=a&&r[l.key]||null,u=Object.assign({},l,{key:s+"."+l.key});return ae?ae.render(u,c,p):d`<div class="field-error">FieldRegistry not initialized</div>`})}
          </div>
        </fieldset>
        ${a?d``:this.renderError(r,d)}
      </div>
    `}getValue(e,t){if(t.extendFields&&Array.isArray(t.extendFields))return null;let r=t.nestedFields||[],s=this.getFieldKey(t),n={};for(let o of r){let a=this.getNestedFieldValue(e,o,s);a!=null&&a!==""&&(n[o.key]=a)}return Object.keys(n).length>0?n:null}getNestedFieldValue(e,t,r){let s=r+"."+(t.key||t.name);if(ae){let n=Object.assign({},t,{key:s});return ae.getValue(e,n)}switch(t.fieldType){case"array":return e.getAll(s).filter(l=>l!=="");case"boolean":let o=e.get(s);return o==="true"||o==="on";case"short":case"numeric":case"float":let a=e.get(s);return a?Number(a):t.defaultValue;default:return e.get(s)}}getDefaultValue(e){if(e.extendFields)return{};let t=e.nestedFields||[],r={};for(let s of t)s.defaultValue!==void 0&&s.defaultValue!==null&&(r[s.key]=s.defaultValue);return r}};var Et=d`<style>
  .yaml-label { margin-bottom: 6px; font-weight: 500; }
  .yaml-content {
    min-height: 80px; max-height: 300px; overflow-y: auto;
    padding: 12px; margin: 0;
    font-size: 13px; line-height: 1.5;
    background: #f8f9fa; border: 1px solid #e9ecef; border-radius: 4px;
    white-space: pre-wrap; word-break: break-all;
  }
  .yaml-textarea {
    width: 100%; min-height: 240px; padding: 12px; box-sizing: border-box;
    font-size: 13px; line-height: 1.5;
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
    border: 1px solid var(--border, #ced4da); border-radius: var(--radius-sm, 4px);
    resize: vertical;
  }
  .yaml-textarea:focus { outline: none; border-color: var(--primary, #2563eb); }
  .yaml-readonly { background: #f8f9fa; color: var(--text, #1f2937); cursor: text; }
</style>`,B=class extends g{static get fieldType(){return"yaml"}_isReadOnly(e){return!!(e.readOnly||e.readonly)}render(e,t,r){let s=t??e.defaultValue??"",n=e.displayName?d`<label class="yaml-label">${this.escapeHtml(e.displayName)}</label>`:"",o=e.description?d`<div class="hint">${this.escapeHtml(e.description)}</div>`:"";if(this._isReadOnly(e))return d`
        ${Et}
        <div class="form-group yaml-display">
          ${n} ${o}
          <textarea class="yaml-textarea yaml-readonly" readonly .value=${s}
                    @click=${l=>l.target.select()}
                    title="点击全选,Ctrl+C 复制"></textarea>
        </div>
      `;let a=this.getFieldKey(e);return d`
      ${Et}
      <div class="form-group yaml-edit">
        ${n} ${o}
        <textarea id="${a}" name="${a}" class="yaml-textarea"
                  placeholder="${e.placeholder||""}" .value=${s}></textarea>
        ${this.renderError(r,d)}
      </div>
    `}getValue(e,t){let r=this.getFieldKey(t);if(this._isReadOnly(t)){let n=t.defaultValue;return n??""}let s=e.get(r);return s??t.defaultValue??""}getDefaultValue(e){return e.defaultValue}};var x="ecat-app-dialog-host",Ct="ecat-app-dialog-styles";function cr(){if(document.getElementById(Ct))return;let i=document.createElement("style");i.id=Ct,i.textContent=`
    #${x} {
      position: fixed; inset: 0; z-index: 2147483000;
      display: none; align-items: center; justify-content: center;
      background: rgba(15, 23, 42, 0.45);
      font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
    }
    #${x}.open { display: flex; }
    #${x} .ecat-dlg {
      background: #fff; border-radius: 12px; width: min(440px, 92vw);
      box-shadow: 0 20px 50px rgba(0,0,0,.25); overflow: hidden;
    }
    #${x} .ecat-dlg-title {
      padding: 16px 20px 0; font-size: 16px; font-weight: 600; color: #111827;
    }
    #${x} .ecat-dlg-body {
      padding: 12px 20px 8px; font-size: 14px; line-height: 1.55; color: #374151;
      white-space: pre-wrap; word-break: break-word; max-height: 60vh; overflow: auto;
    }
    #${x} .ecat-dlg-actions {
      display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px 16px;
    }
    #${x} .ecat-dlg-btn {
      min-width: 72px; padding: 8px 16px; border-radius: 6px; border: 1px solid #d1d5db;
      background: #fff; color: #374151; font-size: 14px; cursor: pointer;
    }
    #${x} .ecat-dlg-btn:hover { background: #f9fafb; }
    #${x} .ecat-dlg-btn.primary {
      background: #2563eb; border-color: #2563eb; color: #fff;
    }
    #${x} .ecat-dlg-btn.primary:hover { background: #1d4ed8; }
    #${x} .ecat-dlg-btn:focus { outline: 2px solid #93c5fd; outline-offset: 1px; }
  `,document.head.appendChild(i)}function ur(){cr();let i=document.getElementById(x);return i||(i=document.createElement("div"),i.id=x,i.setAttribute("role","presentation"),document.body.appendChild(i)),i}function pr({title:i,message:e,showCancel:t}){return new Promise(r=>{let s=ur(),n=document.activeElement;s.innerHTML=`
      <div class="ecat-dlg" role="alertdialog" aria-modal="true" aria-labelledby="ecat-dlg-title">
        <div class="ecat-dlg-title" id="ecat-dlg-title"></div>
        <div class="ecat-dlg-body"></div>
        <div class="ecat-dlg-actions"></div>
      </div>
    `,s.querySelector(".ecat-dlg-title").textContent=i,s.querySelector(".ecat-dlg-body").textContent=e;let o=s.querySelector(".ecat-dlg-actions"),a=document.createElement("button");a.type="button",a.className="ecat-dlg-btn primary",a.textContent="\u786E\u5B9A";let l=null;t&&(l=document.createElement("button"),l.type="button",l.className="ecat-dlg-btn",l.textContent="\u53D6\u6D88",o.appendChild(l)),o.appendChild(a);let c=u=>{if(s.classList.remove("open"),s.innerHTML="",document.removeEventListener("keydown",p),n&&typeof n.focus=="function")try{n.focus()}catch{}r(u)},p=u=>{u.key==="Escape"&&t?(u.preventDefault(),c(!1)):u.key==="Enter"&&(u.preventDefault(),c(!0))};a.addEventListener("click",()=>c(!0)),l&&l.addEventListener("click",()=>c(!1)),s.addEventListener("click",u=>{u.target===s&&t&&c(!1)}),document.addEventListener("keydown",p),s.classList.add("open"),a.focus()})}function Vt(i,e="\u786E\u8BA4"){return pr({title:e,message:String(i??""),showCancel:!0})}var hr=`
.ecat-config-table-masterdetail{width:100%;border-collapse:collapse;background:var(--bg,#fff);font-size:13px;table-layout:auto}
.ecat-config-table-masterdetail th,.ecat-config-table-masterdetail td{border:1px solid var(--border,#e0e0e0);padding:6px 8px;vertical-align:middle;text-align:left}
.ecat-config-table-masterdetail thead th{background:var(--secondary-light,#f1f5f9);font-weight:600;color:var(--text,#1f2937);white-space:nowrap}
.ecat-config-table-masterdetail .unit-col{width:90px}
.ecat-config-table-masterdetail .unit-cell{color:var(--text-muted,#6b7280);font-size:12px;white-space:nowrap;text-align:center;vertical-align:middle}
.ecat-config-table-masterdetail .op-col{width:80px;text-align:center}
.ecat-config-table-masterdetail .op-cell{text-align:center;white-space:nowrap;vertical-align:middle}
.ecat-config-table-masterdetail .op-cell .btn-icon{border:none;background:none;cursor:pointer;padding:5px;margin:0 2px;color:var(--text-muted,#6b7280);border-radius:var(--radius-sm,6px);display:inline-flex;align-items:center;justify-content:center;transition:all .15s;vertical-align:middle}
.ecat-config-table-masterdetail .op-cell .op-sep{display:inline-block;width:1px;height:18px;background:var(--border,#e0e0e0);margin:0 4px;vertical-align:middle}
.ecat-config-table-masterdetail .op-cell .btn-icon:hover{background:var(--secondary-light,#f1f5f9);color:var(--text,#1f2937)}
.ecat-config-table-masterdetail .op-cell .btn-icon.btn-remove:hover{color:var(--danger,#ef4444);background:#fef2f2}
.ecat-config-table-masterdetail .op-cell .btn-icon svg{width:16px;height:16px;display:block}
/* \u5355\u5143\u683C\u5185 field-renderer \u628A label \u5305\u5728 .form-group \u91CC(\u5D4C\u5957),\u4E0E\u8868\u5934/\u8BE6\u60C5\u5916\u5C42 label \u91CD\u590D,\u9690\u85CF\u4E4B\u3002
   \u8BE6\u60C5\u533A boolean \u5B57\u6BB5(\u53EF\u5199)\u7684 label \u5728 .checkbox-wrapper \u5185(\u5B59\u7EA7,\u975E .form-group \u76F4\u63A5\u5B50),
   \u6545\u987B\u5355\u72EC\u5217 .checkbox-wrapper > label,\u5426\u5219"\u53EF\u5199*"\u4F1A\u663E\u793A\u4E24\u4EFD(detail-field \u5916\u5C42 label + checkbox \u65C1 label)\u3002 */
.ecat-config-table-masterdetail td .form-group > label,
.detail-field .form-group > label,
.detail-field .checkbox-wrapper > label{display:none}
/* \u5355\u5143\u683C\u5185 field-renderer \u628A\u8F93\u5165\u5305\u5728 .form-group \u91CC,\u800C\u5168\u5C40 .form-group \u6709 margin-bottom:20px(flow-form.js)
   \u4F1A\u6491\u9AD8\u6BCF\u4E2A cell\u3001\u884C\u5E95\u51ED\u7A7A\u591A\u4E00\u622A\u7A7A\u767D(\u770B\u7740\u50CF"\u591A\u4E00\u884C")\u3002\u8868\u683C\u884C\u8981\u7D27\u51D1,\u8FD9\u91CC\u6E05\u96F6 cell \u5185 form-group \u7684 margin\u3002 */
.ecat-config-table-masterdetail td .form-group{margin:0}
.ecat-config-table-masterdetail td input,.ecat-config-table-masterdetail td select{padding:4px 6px;border:1px solid var(--border,#e0e0e0);border-radius:var(--radius-sm,6px);font-size:13px;width:100%;box-sizing:border-box;background:var(--bg,#fff)}
.ecat-config-table-masterdetail td input:focus,.ecat-config-table-masterdetail td select:focus{outline:none;border-color:var(--primary,#4f6ef7)}
.ecat-config-table-masterdetail td input[type="checkbox"]{width:auto}
.btn-add-row{margin-top:10px;padding:7px 14px;cursor:pointer;border:1px solid var(--primary,#4f6ef7);background:var(--primary-light,#eef2ff);color:var(--primary,#4f6ef7);border-radius:var(--radius-sm,6px);font-size:13px;font-weight:500;transition:all .2s;display:inline-flex;align-items:center;gap:5px}
.btn-add-row:hover{background:var(--primary,#4f6ef7);color:#fff}
.btn-add-row svg{width:16px;height:16px;display:block}
.detail-row>td{background:var(--bg-gray,#f8f9fa);padding:0;border-top:none;border-left:1px solid var(--border,#e0e0e0);border-right:1px solid var(--border,#e0e0e0)}
.detail-card{padding:12px 16px;border-left:3px solid var(--primary,#4f6ef7);background:var(--bg,#fff);margin:2px 0 8px;border-radius:0 var(--radius-sm,6px) var(--radius-sm,6px) 0}
.detail-group{margin-bottom:14px}
.detail-group:last-child{margin-bottom:0}
.detail-group-title{font-size:12px;font-weight:600;color:var(--primary,#4f6ef7);margin-bottom:8px}
.detail-fields{display:flex;flex-wrap:wrap;gap:10px 20px}
.detail-field{display:flex;flex-direction:column;min-width:150px;flex:0 0 auto}
.detail-field > label{font-size:12px;color:var(--text-muted,#6b7280);margin-bottom:4px;white-space:nowrap;font-weight:500}
.detail-field input,.detail-field select,.detail-field textarea{padding:5px 8px;border:1px solid var(--border,#e0e0e0);border-radius:var(--radius-sm,6px);font-size:13px;min-width:120px;background:var(--bg,#fff)}
.detail-field input:focus,.detail-field select:focus{outline:none;border-color:var(--primary,#4f6ef7)}
.detail-empty{color:var(--text-muted,#6b7280);font-size:12px;padding:6px 0}
`,Ee={chevronRight:d`<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7"/></svg>`,chevronDown:d`<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 9l-7 7-7-7"/></svg>`,trash:d`<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"/></svg>`,plus:d`<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 4v16m8-8H4"/></svg>`},le=null;function Tt(i){le=i}function Rt(i,e,t,r,s){let n=le?le.getRenderer(i.fieldType):null;if(!n)return d`<span class="field-error">无渲染器:${i.fieldType}</span>`;let o=Object.assign({},i,{key:`${e}.${t}.${i.key}`});return n.render(o,r,s)}var Ce=class extends _{createRenderRoot(){return this}constructor(){super(),this._expanded={}}connectedCallback(){super.connectedCallback();let e=this.getRootNode();if(e&&e.getElementById&&!e.getElementById("ecat-table-field-styles")){let t=document.createElement("style");t.id="ecat-table-field-styles",t.textContent=hr,(e.head||e).appendChild(t)}}willUpdate(e){if(e.has("value")||e.has("field")){let t=Array.isArray(this.value)?this.value:Array.isArray(this.field&&this.field.defaultValue)?this.field.defaultValue:[];t.length>0?this._rows=t.map(r=>Object.assign({},r)):this._rows===void 0&&(this._rows=[])}}_fieldKey(){return this.field&&(this.field.key||this.field.name)||""}_columns(){return this.field&&this.field.columns||[]}_allowAdd(){return!(this.field&&this.field.allowAdd===!1)}_allowDelete(){return!(this.field&&this.field.allowDelete===!1)}_mainCols(){return this._columns().filter(e=>!e.displayGroup||e.displayGroup==="main")}_detailCols(){return this._columns().filter(e=>e.displayGroup==="detail")}_addRow(){let e=this._rows.length,t={};for(let r of this._columns()){let s=r.defaultValue;s!=null&&s!==""&&(t[r.key]=s)}this._rows=[...this._rows,t],this._expanded=Object.assign({},this._expanded,{[e]:!0})}async _removeRow(e){if(!await Vt("\u786E\u5B9A\u5220\u9664\u8BE5\u6D4B\u70B9?"))return;this._rows=this._rows.filter((r,s)=>s!==e);let t={};Object.keys(this._expanded).forEach(r=>{let s=+r;s<e?t[s]=this._expanded[r]:s>e&&(t[s-1]=this._expanded[r])}),this._expanded=t}_toggle(e){this._expanded=Object.assign({},this._expanded,{[e]:!this._expanded[e]})}_onCellChange(e,t,r){let s=this._columns().find(a=>a.key===t);if(s&&s.fieldType==="table")return;if(s&&s.fieldType==="multi_select"){let a=`${this._fieldKey()}.${e}.${t}`,l=Array.from(this.querySelectorAll(`input[name="${a}"]:checked`)).map(c=>c.value);this._rows=this._rows.map((c,p)=>p===e?Object.assign({},c,{[t]:l}):c);return}let n=r&&r.target,o=n?n.type==="checkbox"?n.checked:n.value:void 0;o!==void 0&&(this._rows=this._rows.map((a,l)=>l===e?Object.assign({},a,{[t]:o}):a))}_filteredCol(e,t){if(!t||!t.dependsOn)return t;let r=e&&e[t.dependsOn];if(!r)return Object.assign({},t,{options:[]});let s=r+".",n=Array.isArray(t.options)?t.options.filter(o=>String(o.value!=null?o.value:o).startsWith(s)):[];return Object.assign({},t,{options:n})}_matchShowWhen(e,t){if(!t)return!0;let r=t.indexOf("=");if(r<0)return!0;let s=t.substring(0,r);return t.substring(r+1).split("|").includes(String(e[s]))}_groupedDetail(e){let t=new Map;for(let r of this._detailCols()){if(!this._matchShowWhen(e,r.showWhen))continue;let s=r.group||"\u5176\u4ED6";t.has(s)||t.set(s,[]),t.get(s).push(r)}return[...t.entries()]}_unitDisplay(e){if(e.unit){let t=this._detailCols().find(s=>s.key==="unit"),r=t&&Array.isArray(t.options)?t.options:null;if(r){let s=r.find(n=>(n.value!=null?n.value:n)===e.unit);if(s&&s.label)return s.label}return e.unit}return"\u65E0"}_cellError(e,t){return this.error&&typeof this.error=="object"&&!Array.isArray(this.error)&&this.error[e]&&this.error[e][t]||null}render(){let e=this._fieldKey(),t=this._mainCols(),r=this._detailCols().length>0,s=this._allowDelete()||r,n=t.length+(r?1:0)+(s?1:0);return d`
      <div class="form-group table-group" data-field-key="${e}">
        <label>${this.field&&this.field.displayName||e}${this.field&&this.field.required?d`<span class="required">*</span>`:""}</label>
        <table class="ecat-config-table ecat-config-table-masterdetail">
          <thead>
            <tr>
              ${t.map(o=>d`<th>${o.displayName||o.key}${o.required?d`<span class="required">*</span>`:""}</th>`)}
              ${r?d`<th class="unit-col">单位</th>`:""}
              ${s?d`<th class="op-col">操作</th>`:""}
            </tr>
          </thead>
          <tbody>
            ${this._rows.map((o,a)=>d`
              <tr data-row="${a}">
                ${t.map(l=>d`<td @change=${c=>this._onCellChange(a,l.key,c)}>${Rt(this._filteredCol(o,l),e,a,o[l.key],this._cellError(a,l.key))}</td>`)}
                ${r?d`<td class="unit-cell">${this._unitDisplay(o)}</td>`:""}
                ${s?d`
                <td class="op-cell">
                  ${r?d`<button type="button" class="btn-icon btn-expand" @click=${()=>this._toggle(a)} title="${this._expanded[a]?"\u6536\u8D77\u8BE6\u60C5":"\u5C55\u5F00\u8BE6\u60C5"}">${this._expanded[a]?Ee.chevronDown:Ee.chevronRight}</button><span class="op-sep" role="separator"></span>`:""}
                  ${this._allowDelete()?d`<button type="button" class="btn-icon btn-remove" @click=${()=>this._removeRow(a)} title="删除测点">${Ee.trash}</button>`:""}
                </td>
                `:""}
              </tr>
              ${r?d`
                <tr class="detail-row" style="${this._expanded[a]?"":"display:none"}"><td colspan="${n}">
                  <div class="detail-card">
                    ${this._groupedDetail(o).map(([l,c])=>d`
                      <div class="detail-group">
                        <div class="detail-group-title">${l}</div>
                        <div class="detail-fields">
                          ${c.map(p=>d`
                            <div class="detail-field" @change=${u=>this._onCellChange(a,p.key,u)}>
                              <label>${p.displayName||p.key}${p.required?d`<span class="required">*</span>`:""}</label>
                              ${Rt(this._filteredCol(o,p),e,a,o[p.key],this._cellError(a,p.key))}
                            </div>
                          `)}
                        </div>
                      </div>
                    `)}
                    ${this._groupedDetail(o).length===0?d`<div class="detail-empty">无附加配置项</div>`:""}
                  </div>
                </td></tr>
              `:""}
            `)}
          </tbody>
        </table>
        ${this._allowAdd()?d`<button type="button" class="btn-add-row" @click=${()=>this._addRow()}>${Ee.plus} 添加测点</button>`:""}
      </div>
    `}};F(Ce,"properties",{field:{type:Object},value:{type:Object},error:{type:Object},_rows:{state:!0},_expanded:{state:!0}});customElements.define("table-field-renderer",Ce);var W=class extends g{static get fieldType(){return"table"}render(e,t,r){return d`<table-field-renderer .field=${e} .value=${t} .error=${r}></table-field-renderer>`}getValue(e,t){let r=t.key||t.name,s=t.columns||[],n=r+".",o=new Set,a=e.keys?Array.from(e.keys()):Object.keys(e);for(let c of a)if(typeof c=="string"&&c.startsWith(n)){let p=c.substring(n.length),u=p.indexOf(".");u>0&&o.add(parseInt(p.substring(0,u),10))}let l=[];for(let c of[...o].sort((p,u)=>p-u)){let p={};for(let u of s){let f=le?le.getRenderer(u.fieldType):null,h=Object.assign({},u,{key:`${n}${c}.${u.key}`}),y=f?f.getValue(e,h):void 0;y!=null&&y!==""&&(p[u.key]=y)}l.push(p)}return l}getDefaultValue(e){return Array.isArray(e.defaultValue)?e.defaultValue:[]}};function qe(){v.register(D),v.register(K),v.register(U),v.register(j),v.register(I),v.register(R),v.register(z),v.register(oe),v.register(q),v.register(B),v.register(W),v.renderers.set("select",new R),v.renderers.set("dynamic_enum",new R),St(v),Tt(v),console.info("[FieldRenderers] All field renderers registered:",v.getRegisteredTypes())}qe();var G=class extends _{constructor(){super(),this.flowId="",this.stepId="",this.schema={},this.data={},this.errors={},this.navigation={},this.loading=!1}get isFirstStep(){return this.navigation?.isFirstStep??!0}get isLastStep(){return this.navigation?.isLastStep??!1}handleSubmit(e){e.preventDefault();let t=this.shadowRoot.querySelector("form");if(!t)return;let r=new FormData(t),s=this.schema?.fields||[],n={};for(let o of s){let a=o.key||o.name;n[a]=v.getValue(r,o)}this.dispatchEvent(new CustomEvent("flow-submit",{detail:{flowId:this.flowId,stepId:this.stepId,userInput:n},bubbles:!0,composed:!0}))}handlePrevious(e){e.preventDefault();let t=this.shadowRoot.querySelector("form"),r=t?new FormData(t):new FormData,s=this.schema?.fields||[],n={};for(let o of s){let a=o.key||o.name;n[a]=v.getValue(r,o)}this.dispatchEvent(new CustomEvent("flow-previous",{detail:{flowId:this.flowId,stepId:this.stepId,userInput:n},bubbles:!0,composed:!0}))}handleInput(e){let t=e.target.name||e.target.id;t&&this.errors&&this.errors[t]&&(this.errors={...this.errors},delete this.errors[t],this.requestUpdate())}renderField(e){let t=e.key||e.name,n=((this.data?.step_inputs||{})[this.stepId]||{})[t]??e.defaultValue??"",o=this.errors[t];return v.render(e,n,o)}render(){let e=this.schema?.fields||[],t=this.schema?.title||"\u914D\u7F6E",r=this.schema?.description||"";return d`
      <div class="form-container">
        <h3 class="step-title">${we(t)}</h3>
        ${r?d`<p class="step-description">${we(r)}</p>`:""}

        <form @submit=${this.handleSubmit} @input=${this.handleInput}>
          ${e.map(s=>this.renderField(s))}

          <div class="button-group">
            ${this.isFirstStep?"":d`
              <button
                type="button"
                class="btn btn-secondary"
                @click=${this.handlePrevious}
                ?disabled=${this.loading}
              >
                ← 上一步
              </button>
            `}
            <button
              type="submit"
              class="btn btn-primary"
              ?disabled=${this.loading}
            >
              ${this.loading?"\u5904\u7406\u4E2D...":"\u4E0B\u4E00\u6B65 \u2192"}
            </button>
          </div>
        </form>
      </div>
    `}};F(G,"styles",ut`
    :host { display: block; }
    .form-container { padding: 20px; }
    .step-title {
      font-size: 20px;
      font-weight: 600;
      color: #1f2937;
      margin-bottom: 8px;
    }
    .step-description {
      color: #6b7280;
      font-size: 14px;
      margin-bottom: 20px;
    }
    .form-group { margin-bottom: 20px; }
    .form-group label {
      display: block;
      font-weight: 500;
      color: #374151;
      margin-bottom: 6px;
    }
    .form-group label .required { color: #ef4444; margin-left: 2px; }
    .form-group input,
    .form-group select {
      width: 100%;
      padding: 10px 12px;
      border: 1px solid #d1d5db;
      border-radius: 6px;
      font-size: 14px;
      box-sizing: border-box;
    }
    .form-group input:focus,
    .form-group select:focus {
      outline: none;
      border-color: #667eea;
      box-shadow: 0 0 0 3px rgba(102, 126, 234, 0.2);
    }
    .form-group input[readonly] {
      background: #f3f4f6;
      color: #6b7280;
      cursor: default;
    }
    .form-group input[readonly]:focus {
      border-color: #d1d5db;
      box-shadow: none;
    }
    .form-group .error {
      font-size: 12px;
      color: #ef4444;
      margin-top: 4px;
    }
    .form-group .hint {
      font-size: 12px;
      color: #6b7280;
      margin-top: 4px;
    }
    .checkbox-wrapper {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .checkbox-wrapper input[type="checkbox"] {
      width: 18px;
      height: 18px;
      cursor: pointer;
    }
    .checkbox-wrapper label {
      font-weight: normal;
      margin: 0;
      cursor: pointer;
    }
    .button-group {
      display: flex;
      gap: 12px;
      margin-top: 24px;
      padding-top: 20px;
      border-top: 1px solid #e5e7eb;
    }
    .btn {
      padding: 10px 24px;
      border: none;
      border-radius: 6px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
      transition: all 0.2s;
    }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-primary {
      background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
      color: white;
    }
    .btn-secondary {
      background: #6b7280;
      color: white;
    }
    .readonly-field {
      padding: 10px 12px;
      background: #f3f4f6;
      border-radius: 6px;
      color: #6b7280;
      font-size: 14px;
    }
    /* Array Field Styles */
    .array-container {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .array-item {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .array-item input {
      flex: 1;
    }
    .add-btn,
    .remove-btn {
      padding: 6px 12px;
      border: none;
      border-radius: 4px;
      font-size: 14px;
      cursor: pointer;
      transition: all 0.2s;
    }
    .add-btn {
      background: #e0e7ff;
      color: #4f46e5;
    }
    .add-btn:hover {
      background: #c7d2fe;
    }
    .remove-btn {
      background: #fee2e2;
      color: #dc2626;
      width: 32px;
      height: 36px;
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 18px;
      line-height: 1;
    }
    .remove-btn:hover {
      background: #fecaca;
    }
    /* Array Checkbox Group */
    .checkbox-group {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    /* Object Field Styles */
    .object-group fieldset,
    .nested-fieldset {
      border: 1px solid #d1d5db;
      border-radius: 6px;
      padding: 16px;
      margin: 0;
    }
    .object-legend,
    .nested-fieldset legend {
      font-size: 13px;
      font-weight: 500;
      color: #6b7280;
      padding: 0 8px;
      margin: 0;
    }
    .nested-fieldset legend .required {
      color: #ef4444;
      margin-left: 2px;
    }
    .nested-fields .form-group {
      margin-bottom: 12px;
    }
    .nested-fields .form-group:last-child {
      margin-bottom: 0;
    }
    /* Schema Nested Group */
    .nested-group {
      margin-bottom: 20px;
    }
    .nested-group:last-child {
      margin-bottom: 0;
    }
  `),F(G,"properties",{flowId:{type:String},stepId:{type:String},schema:{type:Object},data:{type:Object},errors:{type:Object},navigation:{type:Object},loading:{type:Boolean}});customElements.define("flow-form",G);function Ft({base:i="/core-api/config-flows",entryBase:e="/core-api/config-flow/entries"}={}){let t=s=>s.json(),r=(s,n)=>fetch(s,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(n)}).then(t);return{getProviders(){return fetch(`${i}/providers`).then(t)},startFlow(s){return r(`${i}/start`,{providerCoordinate:s})},getStatus(s){return fetch(`${i}/${encodeURIComponent(s)}`).then(t)},submitStep(s,n,o){return r(`${i}/step`,{flowId:s,stepId:n,userInput:o})},goPrevious(s){return r(`${i}/previous`,{flowId:s})},cancelFlow(s){return fetch(`${i}/${encodeURIComponent(s)}`,{method:"DELETE"}).then(t)},listEntries(s=""){let n=s?`${e}?coordinate=${encodeURIComponent(s)}`:e;return fetch(n).then(t)},getEntry(s){return fetch(`${e}/${encodeURIComponent(s)}`).then(t)},deleteEntry(s){return fetch(`${e}/${encodeURIComponent(s)}`,{method:"DELETE"}).then(t)},enableEntry(s){return r(`${e}/enable`,{entryId:s})},disableEntry(s){return r(`${e}/disable`,{entryId:s})},reconfigureEntry(s){return r(`${e}/reconfigure`,{entryId:s})},listDiscoveries(){return fetch(`${i}/discoveries`).then(t)},ignoreDiscovery(s,n,o=""){return r(`${e}/ignore`,{coordinate:s,uniqueId:n,title:o})},unignoreDiscovery(s){return r(`${e}/unignore`,{uniqueId:s})},listIgnored(){return fetch(`${e}/ignored`).then(t)}}}var fr=Ft();typeof window<"u"&&(window.__ecatConfigFlowVersion=We);export{z as ArrayFieldRenderer,I as BooleanFieldRenderer,We as CONFIG_FLOW_LIB_VERSION,R as EnumFieldRenderer,v as FieldRegistry,g as FieldRenderer,U as FloatFieldRenderer,G as FlowForm,K as NumericFieldRenderer,q as SchemaFieldRenderer,j as ShortFieldRenderer,W as TableFieldRenderer,D as TextFieldRenderer,B as YamlFieldRenderer,fr as configFlowApi,Ft as createConfigFlowApi,we as escapeHtml,kt as isBooleanField,At as isNumericField,qe as registerAllFieldRenderers,ar as transformFormData};
//# sourceMappingURL=ecat-config-flow.esm.js.map
