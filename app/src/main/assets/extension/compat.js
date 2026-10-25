// Firefox promise APIs; only the Chrome-specific injection entry point is adapted.
// Loaded in each isolated extension context, never injected into the site's JS world.
var chrome = Object.assign({}, browser, {
  scripting: {
    async executeScript({target, files}) {
      const results=[];
      for(const file of files) {
        results.push(...await browser.tabs.executeScript(target.tabId,{file,runAt:'document_idle'}));
      }
      return results;
    }
  }
});
